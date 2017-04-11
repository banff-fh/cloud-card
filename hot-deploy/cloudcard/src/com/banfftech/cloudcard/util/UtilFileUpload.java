package com.banfftech.cloudcard.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
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
        String fileSuffixTmp = fileName.substring(fileName.lastIndexOf(".") + 1);
        String fileSuffix = fileSuffixTmp.substring(0,fileSuffixTmp.lastIndexOf("}"));
        /*if (UtilValidate.isNotEmpty(fileSuffix)) {
            GenericValue gv;
            try {
                gv = delegator.findOne("FileExtension", true, UtilMisc.toMap("fileExtensionId", fileSuffix.toLowerCase()));
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
            if (gv != null)
                contentType = gv.getString("mimeTypeId");
        }*/
        
        //通过文件名判断并获取OSS服务文件上传时文件的contentType  
        contentType = getContentType(fileSuffix);
        
        if (UtilValidate.isNotEmpty(imageDataBytes)) {
            InputStream input = new ByteArrayInputStream(imageDataBytes.array());
            // 创建OSSClient实例
            OSSClient client = getNewOssClient(delegator);
            ObjectMetadata objectMeta = new ObjectMetadata();
            objectMeta.setContentLength(imageDataBytes.array().length);
            objectMeta.setContentEncoding("UTF-8");
            // 可以在metadata中标记文件类型
            objectMeta.setContentType(contentType);
            String key = UUID.randomUUID().toString() + System.currentTimeMillis();
            PutObjectResult pr = client.putObject(BUCKET_NAME, key, input, objectMeta);
            // pr 的结果需要判断下吧
            client.shutdown();
            result.put("filePath", key);
        }

        return result;

    }
    
    /**
     * 上传文件，并返回图片路径及图片名称
     *
     * @param request
     * @return
     * @throws Exception
     */
    public static String uploadPictureWall(HttpServletRequest request, HttpServletResponse response)
			throws GenericServiceException {
		// Servlet Head
		Delegator delegator = (Delegator) request.getAttribute("delegator");
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		HttpSession session = request.getSession();
		GenericValue userLogin = (GenericValue) session.getAttribute("userLogin");		
		String mimeType = "image/jpeg";
		
		try {
			ServletFileUpload dfu = new ServletFileUpload(new DiskFileItemFactory(10240, null));
			List<FileItem> items = dfu.parseRequest(request);
			int itemSize = 0;
			if(null!=items){
				itemSize = items.size();
			}
			
			for (FileItem item : items) {
				InputStream in = item.getInputStream();
				String fileName = item.getName();
				
				String fileSuffix = fileName.substring(fileName.lastIndexOf(".") + 1);
				if (UtilValidate.isNotEmpty(fileSuffix)) {
					GenericValue gv;
					try {
						gv = delegator.findOne("FileExtension", true, UtilMisc.toMap("fileExtensionId", fileSuffix.toLowerCase()));
						if (gv != null){
							mimeType = gv.getString("mimeTypeId");
						}
					} catch (GenericEntityException e) {
						Debug.logError(e.getMessage(), module);
					}
					
				}
				
				if (fileName != null && (!fileName.trim().equals(""))) {
		            // 创建OSSClient实例
		            OSSClient client = getNewOssClient(delegator);
		            ObjectMetadata objectMeta = new ObjectMetadata();
                    objectMeta.setContentLength(item.getSize());
                    // 可以在metadata中标记文件类型
                    objectMeta.setContentType(mimeType);
                    String key = UUID.randomUUID().toString() + System.currentTimeMillis();
                    InputStream input = item.getInputStream();
		            PutObjectResult pr = client.putObject(BUCKET_NAME, key, in, objectMeta);
		            // pr 的结果需要判断下吧
		            client.shutdown();
		            
		            // 1.CREATE DATA RESOURCE
		    		Map<String, Object> createDataResourceMap = UtilMisc.toMap("userLogin", userLogin, "partyId", "admin",
		    				"dataResourceTypeId", "LOCAL_FILE", "dataCategoryId", "PERSONAL", "dataResourceName", fileName,
		    				"mimeTypeId", mimeType, "isPublic", "Y", "dataTemplateTypeId", "NONE", "statusId", "CTNT_PUBLISHED",
		    				"objectInfo", key);
		    		Map<String, Object> serviceResultByDataResource = dispatcher.runSync("createDataResource",
		    				createDataResourceMap);
		    		String dataResourceId = (String) serviceResultByDataResource.get("dataResourceId");

		    		// 2.CREATE CONTENT  type=ACTIVITY_PICTURE
		    		Map<String, Object> createContentMap = UtilMisc.toMap("userLogin", userLogin, "contentTypeId",
		    				"ACTIVITY_PICTURE", "mimeTypeId", mimeType, "dataResourceId", dataResourceId, "partyId", "admin");
		    		Map<String, Object> serviceResultByCreateContentMap = dispatcher.runSync("createContent", createContentMap);
				}
			}
		} catch (Exception e) {
			Debug.logError(e.getMessage(), module);
		}
		
		

		return "success";
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
    
    /**  
     * 通过文件名判断并获取OSS服务文件上传时文件的contentType  
     * @param fileName 文件名 
     * @return 文件的contentType    
     */    
     public static final String getContentType(String fileExtension){    
        if("bmp".equalsIgnoreCase(fileExtension)) return "image/bmp";  
        if("gif".equalsIgnoreCase(fileExtension)) return "image/gif";  
        if("jpeg".equalsIgnoreCase(fileExtension) || "jpg".equalsIgnoreCase(fileExtension)  || "png".equalsIgnoreCase(fileExtension) ) return "image/jpeg";  
        if("html".equalsIgnoreCase(fileExtension)) return "text/html";  
        if("txt".equalsIgnoreCase(fileExtension)) return "text/plain";  
        if("vsd".equalsIgnoreCase(fileExtension)) return "application/vnd.visio";  
        if("ppt".equalsIgnoreCase(fileExtension) || "pptx".equalsIgnoreCase(fileExtension)) return "application/vnd.ms-powerpoint";  
        if("doc".equalsIgnoreCase(fileExtension) || "docx".equalsIgnoreCase(fileExtension)) return "application/msword";  
        if("xml".equalsIgnoreCase(fileExtension)) return "text/xml";  
        return "text/html";    
     } 

}
