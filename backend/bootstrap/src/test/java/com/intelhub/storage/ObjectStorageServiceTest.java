package com.intelhub.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageServiceTest {
    @Test
    void deletePrefixRemovesEveryPageOfMinioObjects() {
        S3Client s3 = mock(S3Client.class);
        ObjectStorageService storage = new ObjectStorageService(s3, "documents");
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder().contents(S3Object.builder().key("owner/kb/a.txt").build())
                        .isTruncated(true).nextContinuationToken("page-2").build(),
                ListObjectsV2Response.builder().contents(S3Object.builder().key("owner/kb/b.txt").build())
                        .isTruncated(false).build());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());

        storage.deletePrefix("owner/kb/");

        var requests = org.mockito.ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3, org.mockito.Mockito.times(2)).listObjectsV2(requests.capture());
        assertThat(requests.getAllValues()).extracting(ListObjectsV2Request::prefix)
                .containsExactly("owner/kb/", "owner/kb/");
        assertThat(requests.getAllValues().get(1).continuationToken()).isEqualTo("page-2");

        var deletes = org.mockito.ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, org.mockito.Mockito.times(2)).deleteObjects(deletes.capture());
        assertThat(deletes.getAllValues()).extracting(request -> request.delete().objects().get(0).key())
                .containsExactly("owner/kb/a.txt", "owner/kb/b.txt");
        assertThat(deletes.getAllValues()).allSatisfy(request -> assertThat(request.bucket()).isEqualTo("documents"));
    }

    @Test
    void deletePrefixSurfacesPartialObjectDeletionFailures() {
        S3Client s3 = mock(S3Client.class);
        ObjectStorageService storage = new ObjectStorageService(s3, "documents");
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder().contents(S3Object.builder().key("owner/kb/a.txt").build())
                        .isTruncated(false).build());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key("owner/kb/a.txt").code("AccessDenied").build()).build());

        assertThatThrownBy(() -> storage.deletePrefix("owner/kb/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("owner/kb/a.txt");
    }

    @Test
    void deletePrefixDoesNotSendDeleteRequestForEmptyPrefix() {
        S3Client s3 = mock(S3Client.class);
        ObjectStorageService storage = new ObjectStorageService(s3, "documents");
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
                ListObjectsV2Response.builder().isTruncated(false).build());

        storage.deletePrefix("owner/kb/");

        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
