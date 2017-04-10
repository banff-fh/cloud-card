package com.banfftech.cloudcard.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.ServiceUtil;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectResult;
import com.banfftech.cloudcard.constant.CloudCardConstant;

public class UtilFileUpload {
    public static String module = UtilFileUpload.class.getName();
    private static String ENDPOINT = "";
    private static String ACCESS_ID = "";
    private static String ACCESS_KEY_SECRET = "";
    private static String BUCKET_NAME = "";

    /**
     * 获取oss相关配置
     *
     * @return
     */
    public static OSSClient getNewOssClient(Delegator delegator) {
        ENDPOINT = EntityUtilProperties.getPropertyValue("cloudcard", "oss.endpoint", delegator);
        ACCESS_ID = EntityUtilProperties.getPropertyValue("cloudcard", "oss.accessKeyId", delegator);
        ACCESS_KEY_SECRET = EntityUtilProperties.getPropertyValue("cloudcard", "oss.accessKeySecret", delegator);
        BUCKET_NAME = EntityUtilProperties.getPropertyValue("cloudcard", "oss.bucketName", delegator);
        return new OSSClient(ENDPOINT, ACCESS_ID, ACCESS_KEY_SECRET);
    }

    /**
     * 接受客户端文件，并上传到OSS，返回远程路径
     *
     * @param request
     * @return
     * @throws Exception
     */
    public static Map<String, Object> uploadOSS(DispatchContext dctx, Map<String, ? extends Object> context) throws IOException {
        // LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        ByteBuffer imageDataBytes = (ByteBuffer) context.get("uploadedFile");// 文件流，必输
        String fileName = (String) context.get("_uploadedFile_fileName");// 文件名，必输
        String contentType = (String) context.get("_uploadedFile_contentType");// 文件mime类型，必输

        Map<String, Object> result = ServiceUtil.returnSuccess();
        String fileSuffix = fileName.substring(fileName.lastIndexOf(".") + 1);
        if (UtilValidate.isEmpty(contentType) && UtilValidate.isNotEmpty(fileSuffix)) {
            GenericValue gv;
            try {
                gv = delegator.findOne("FileExtension", true, UtilMisc.toMap("fileExtensionId", fileSuffix.toLowerCase()));
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
            if (gv != null)
                contentType = gv.getString("mimeTypeId");
        }

        if (UtilValidate.isNotEmpty(imageDataBytes)) {
            InputStream input = new ByteArrayInputStream(imageDataBytes.array());
            // 创建OSSClient实例
            OSSClient client = getNewOssClient(delegator);
            ObjectMetadata objectMeta = new ObjectMetadata();
            objectMeta.setContentLength(imageDataBytes.array().length);
            objectMeta.setContentEncoding("UTF-8");

            // 可以在metadata中标记文件类型
            objectMeta.setContentType(contentType);
            // TODO 目录前缀，可能需要业务类型不同而不同啊
            String OSSFileLocation = "others/";

            // 上传key的时候，必须加上后缀，杀毒软件可能拦截
            String key = OSSFileLocation + UUID.randomUUID() + "." + fileSuffix;
            PutObjectResult pr = client.putObject(BUCKET_NAME, key, input, objectMeta);
            // pr 的结果需要判断下吧
            client.shutdown();
            result.put("filePath", key);
        }

        return result;

    }

    /**
     * 删除oss上的文件
     * 
     * @param key
     */
    public static void delFile(String key) {
        if (UtilValidate.isNotEmpty(key)) {
            OSSClient client = new OSSClient(ENDPOINT, ACCESS_ID, ACCESS_KEY_SECRET);
            client.deleteObject(BUCKET_NAME, key);
        }
    }

}
