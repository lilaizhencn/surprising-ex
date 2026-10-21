package com.surprising.gateway.provider.model;

/** Controller完成Multipart绑定/格式校验后的文档；字节只在同步上传调用内交接。 */
public record KycDocumentUpload(String documentType, byte[] content, String contentType,
                                String filename, String extension) {
}
