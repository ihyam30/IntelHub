package com.intelhub.storage;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.List;

@Service
public class ObjectStorageService {
    private final S3Client s3;
    private final String bucket;

    public ObjectStorageService(S3Client s3, @Value("${intelhub.files.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @PostConstruct
    void ensureBucket() {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                return;
            } catch (RuntimeException missing) {
                try {
                    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    return;
                } catch (RuntimeException raceOrUnavailable) {
                    try {
                        s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                        return;
                    } catch (RuntimeException unavailable) {
                        lastFailure = unavailable;
                    }
                }
            }
            if (attempt < 29) {
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("等待 MinIO 启动时被中断", interrupted);
                }
            }
        }
        throw new IllegalStateException("MinIO 未就绪，无法准备文件桶 " + bucket, lastFailure);
    }

    public void put(String key, byte[] bytes, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(), RequestBody.fromBytes(bytes));
    }

    public byte[] get(String key) throws IOException {
        try (ResponseInputStream<?> input = s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return input.readAllBytes();
        }
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public void deletePrefix(String prefix) {
        String continuationToken = null;
        do {
            var requestBuilder = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
            if (continuationToken != null) requestBuilder.continuationToken(continuationToken);
            var request = requestBuilder.build();
            var listed = s3.listObjectsV2(request);
            List<ObjectIdentifier> objects = listed.contents().stream()
                    .map(item -> ObjectIdentifier.builder().key(item.key()).build())
                    .toList();
            if (!objects.isEmpty()) {
                var deleted = s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                        .delete(Delete.builder().objects(objects).quiet(true).build()).build());
                if (!deleted.errors().isEmpty()) {
                    throw new IllegalStateException("MinIO 删除对象失败: " + deleted.errors().get(0).key());
                }
            }
            continuationToken = Boolean.TRUE.equals(listed.isTruncated()) ? listed.nextContinuationToken() : null;
        } while (continuationToken != null);
    }
}
