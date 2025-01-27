/*
 * Copyright 2000-2021 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.artifacts.s3.preSignedUrl;

import com.amazonaws.HttpMethod;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.text.StringUtil;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import jetbrains.buildServer.artifacts.s3.S3Util;
import jetbrains.buildServer.artifacts.s3.util.ParamUtil;
import jetbrains.buildServer.artifacts.s3.util.S3RegionCorrector;
import jetbrains.buildServer.serverSide.IOGuard;
import jetbrains.buildServer.serverSide.ServerPaths;
import jetbrains.buildServer.serverSide.TeamCityProperties;
import jetbrains.buildServer.util.TimeService;
import jetbrains.buildServer.util.amazon.AWSException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Created by Evgeniy Koshkin (evgeniy.koshkin@jetbrains.com) on 19.07.17.
 */
public class S3PreSignedManagerImpl implements S3PreSignedManager {
  @NotNull
  private static final Logger LOG = Logger.getInstance(S3PreSignedManagerImpl.class.getName());
  @NotNull
  private static final String TEAMCITY_S3_OVERRIDE_CONTENT_DISPOSITION = "teamcity.s3.override.content.disposition.enabled";
  @NotNull
  private final ServerPaths myServerPaths;
  @NotNull
  private final TimeService myTimeService;

  public S3PreSignedManagerImpl(@NotNull final ServerPaths serverPaths,
                                @NotNull final TimeService timeService) {
    myServerPaths = serverPaths;
    myTimeService = timeService;
  }

  @NotNull
  @Override
  public String generateDownloadUrl(@NotNull final HttpMethod httpMethod, @NotNull final String objectKey, @NotNull final S3Settings settings) throws IOException {
    return generateUrl(httpMethod, objectKey, null, null, settings);
  }

  @NotNull
  @Override
  public String generateUploadUrl(@NotNull final String objectKey, @NotNull final S3Settings settings) throws IOException {
    return generateUrl(HttpMethod.PUT, objectKey, null, null, settings);
  }

  @NotNull
  @Override
  public String generateUploadUrlForPart(@NotNull final String objectKey,
                                         final int nPart,
                                         @NotNull final String uploadId,
                                         @NotNull final S3Settings settings) throws IOException {
    return generateUrl(HttpMethod.PUT, objectKey, nPart, uploadId, settings);
  }

  @NotNull
  private String generateUrl(@NotNull final HttpMethod httpMethod,
                             @NotNull final String objectKey,
                             @Nullable final Integer nPart,
                             @Nullable final String uploadId,
                             @NotNull final S3Settings settings) throws IOException {
    try {
      final GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(settings.getBucketName(), objectKey, httpMethod)
        .withExpiration(new Date(myTimeService.now() + settings.getUrlTtlSeconds() * 1000L));
      if (nPart != null) {
        request.addRequestParameter("partNumber", String.valueOf(nPart));
      }
      if (uploadId != null) {
        request.addRequestParameter("uploadId", uploadId);
      }

      if (TeamCityProperties.getBooleanOrTrue(TEAMCITY_S3_OVERRIDE_CONTENT_DISPOSITION)) {
        final List<String> split = StringUtil.split(objectKey, "/");
        if (!split.isEmpty()) {
          request.withResponseHeaders(new ResponseHeaderOverrides()
                                        .withContentDisposition("inline; filename=\"" + split.get(split.size() - 1) + "\""));
        }
      }
      return callS3WithIOGuard(client -> client.generatePresignedUrl(request).toString(), settings);
    } catch (Exception e) {
      final Throwable cause = e.getCause();
      final AWSException awsException = cause != null ? new AWSException(cause) : new AWSException(e);
      final String details = awsException.getDetails();
      if (StringUtil.isNotEmpty(details)) {
        final String message = awsException.getMessage() + details;
        LOG.warnAndDebugDetails(message, cause);
      }
      throw new IOException(String.format("Failed to create pre-signed URL to %s artifact '%s' in bucket '%s': %s",
                                          httpMethod.name().toLowerCase(), objectKey, settings.getBucketName(), awsException.getMessage()), awsException);
    }
  }

  @NotNull
  @Override
  public String startMultipartUpload(@NotNull final String objectKey, @NotNull final S3Settings settings) throws Exception {
    return callS3WithIOGuard(client -> {
      final InitiateMultipartUploadRequest initiateMultipartUploadRequest = new InitiateMultipartUploadRequest(settings.getBucketName(), objectKey);
      final InitiateMultipartUploadResult initiateMultipartUploadResult =
        client.initiateMultipartUpload(initiateMultipartUploadRequest);
      return initiateMultipartUploadResult.getUploadId();
    }, settings);
  }

  @Override
  public void finishMultipartUpload(@NotNull final String uploadId,
                                    @NotNull final String objectKey,
                                    @NotNull final S3Settings settings,
                                    @Nullable final String[] etags,
                                    final boolean isSuccessful) throws IOException {
    callS3WithIOGuard(client -> {
      if (isSuccessful) {
        if (etags == null || etags.length == 0) {
          throw new IllegalArgumentException("Cannot complete multipart request without etags");
        }
        final List<PartETag> partETags = new ArrayList<>();
        for (int i = 0; i < etags.length; i++) {
          partETags.add(new PartETag(i + 1, etags[i]));
        }
        client.completeMultipartUpload(new CompleteMultipartUploadRequest(settings.getBucketName(), objectKey, uploadId, partETags));
      } else {
        client.abortMultipartUpload(new AbortMultipartUploadRequest(settings.getBucketName(), objectKey, uploadId));
      }
      return null;
    }, settings);
  }

  private <T> T callS3WithIOGuard(@NotNull final Function<AmazonS3, T> callable, @NotNull final S3Settings settings) throws IOException {
    return S3Util.withS3ClientShuttingDownImmediately(((S3SettingsImpl)settings).getSettings(), client -> IOGuard.allowNetworkCall(() -> {
      try {
        return callable.apply(client);
      } catch (final Throwable t) {
        if (t instanceof IOException) {
          throw (IOException)t;
        } else {
          throw new IOException(t);
        }
      }
    }));
  }

  @NotNull
  public S3Settings settings(@NotNull final Map<String, String> rawSettings) {
    if (S3Util.getBucketName(rawSettings) == null) {
      throw new IllegalArgumentException("Settings don't contain bucket name");
    }
    final Map<String, String> sslSettings = ParamUtil.putSslValues(myServerPaths, rawSettings);
    return new S3SettingsImpl(S3RegionCorrector.correctRegion(S3Util.getBucketName(sslSettings), sslSettings));
  }

  private static class S3SettingsImpl implements S3Settings {
    @NotNull
    private final Map<String, String> mySettings;

    private S3SettingsImpl(@NotNull final Map<String, String> params) {
      mySettings = params;
    }

    @NotNull
    @Override
    public String getBucketName() {
      return S3Util.getBucketName(mySettings);
    }

    @Override
    public int getUrlTtlSeconds() {
      return S3Util.getUrlTtlSeconds(mySettings);
    }

    @NotNull
    private Map<String, String> getSettings() {
      return mySettings;
    }
  }
}
