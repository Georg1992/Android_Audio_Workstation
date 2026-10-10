package com.georgv.audioworkstation.share

import java.time.Duration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.ServerSideEncryption
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest

internal data class ObjectStat(val exists: Boolean, val size: Long)

internal data class UploadTarget(
    val url: String,
    val headers: Map<String, String>,
)

internal class SharedObjects(config: ShareApiConfig) {
    private val bucket = config.bucket
    private val s3 = S3Client.builder()
        .region(Region.US_EAST_1)
        .httpClient(UrlConnectionHttpClient.create())
        .build()
    private val presigner = S3Presigner.builder().region(Region.US_EAST_1).build()

    fun stat(contentHash: String): ObjectStat {
        val key = storageKey(contentHash)
        return try {
            val head = s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build())
            ObjectStat(exists = true, size = head.contentLength())
        } catch (missing: S3Exception) {
            if (missing.statusCode() != 404) throw missing
            ObjectStat(exists = false, size = -1)
        }
    }

    fun uploadTarget(contentHash: String, size: Long): UploadTarget {
        val key = storageKey(contentHash)
        val request = software.amazon.awssdk.services.s3.model.PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(CONTENT_TYPE)
            .contentLength(size)
            .serverSideEncryption(ServerSideEncryption.AES256)
            .build()
        val presigned = presigner.presignPutObject(
            PutObjectPresignRequest.builder()
                .signatureDuration(URL_LIFETIME)
                .putObjectRequest(request)
                .build(),
        )
        return UploadTarget(
            url = presigned.url().toString(),
            headers = mapOf(
                "Content-Type" to CONTENT_TYPE,
                "x-amz-server-side-encryption" to "AES256",
            ),
        )
    }

    fun downloadUrl(contentHash: String): String {
        val key = storageKey(contentHash)
        val request = software.amazon.awssdk.services.s3.model.GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()
        return presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(URL_LIFETIME)
                .getObjectRequest(request)
                .build(),
        ).url().toString()
    }

    private companion object {
        const val CONTENT_TYPE = "application/octet-stream"
        val URL_LIFETIME: Duration = Duration.ofMinutes(10)
    }
}
