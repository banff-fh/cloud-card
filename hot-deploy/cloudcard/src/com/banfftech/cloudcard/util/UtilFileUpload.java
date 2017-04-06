package com.banfftech.cloudcard.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.ofbiz.base.location.FlexibleLocation;
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

import javolution.util.FastMap;

public class UtilFileUpload {
	public static String module = UtilFileUpload.class.getName();
	private static String ENDPOINT = "";
	private static String ACCESS_ID = "";
	private static String ACCESS_KEY_SECRET = "";
	private static String BUCKET_NAME = "";
	
	
	/**
     * 优先尝试内网链接
     *
     * @return
     */
    public static OSSClient getNewOssClient(Delegator delegator) {
		OSSClient client = new OSSClient(ENDPOINT, ACCESS_ID, ACCESS_KEY_SECRET);
		ENDPOINT = EntityUtilProperties.getPropertyValue("cloudcard","oss.endpoint",delegator);
		ACCESS_ID = EntityUtilProperties.getPropertyValue("cloudcard","oss.accessKeyId",delegator);
		ACCESS_KEY_SECRET = EntityUtilProperties.getPropertyValue("cloudcard","oss.accessKeySecret",delegator);
		BUCKET_NAME = EntityUtilProperties.getPropertyValue("cloudcard","oss.bucketName",delegator);
        return client;
    }
	
    /**
     * 上传文件，并返回图片路径及图片名称
     *
     * @param request
     * @return
     * @throws Exception
     */
    public static Map<String, Object> uploadFile(HttpServletRequest request) throws Exception {
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		Delegator delegator = dispatcher.getDelegator();
        Map<String, Object> context = FastMap.newInstance();
        FileItemFactory factory = new DiskFileItemFactory();
        ServletFileUpload upload = new ServletFileUpload(factory);
        try {
            // 获取文件项List
            @SuppressWarnings("unchecked")
            List<FileItem> list = upload.parseRequest(request);
            // 建立文件项的迭代器
            Iterator<FileItem> it = list.iterator();
            while (it.hasNext()) {
                FileItem fileItem = (FileItem) it.next();
                // 判断是否为表单域，false则是file文件
                if (fileItem.isFormField()) {
                    // 取文件的各个属lla
                    String fileName = fileItem.getFieldName();
                    String fileValue = fileItem.getString("UTF-8");

                    if (!context.containsKey(fileName)) {
                        context.put(fileName, fileValue);
                    } else {
                        String oldValue = (String) context.get(fileName);
                        context.put(fileName, oldValue + ";" + fileValue);
                    }
                } else {
                    if (fileItem.getSize() > 0) {
                        OSSClient client = getNewOssClient(delegator);
                        ObjectMetadata objectMeta = new ObjectMetadata();
                        objectMeta.setContentLength(fileItem.getSize());
                        // 可以在metadata中标记文件类型
                        objectMeta.setContentType("image/jpeg");
                        String key = UUID.randomUUID().toString() + System.currentTimeMillis();
                        InputStream input = fileItem.getInputStream();
                        PutObjectResult pr = client.putObject(BUCKET_NAME, key, input, objectMeta);
                        client.shutdown();
                    }
                }
            }
        } catch (Exception e) {
            Debug.logError(e.getMessage(), module);
        }
        return context;
    }
    
    
    /**
     * 上传文件，并返回图片路径及图片名称
     *
     * @param request
     * @return
     * @throws Exception
     */
    public static Map<String, Object> uploadedFile(DispatchContext dctx, Map<String, ? extends Object> context) throws IOException {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Map<String, Object> result = ServiceUtil.returnSuccess();
        ByteBuffer imageDataBytes = (ByteBuffer) context.get("uploadedFile");
        String _uploadedFile_fileName = (String) context.get("_uploadedFile_fileName");
        String contentType = (String) context.get("_uploadedFile_contentType");
        String fileSuffix = null;
        try {
            int index = _uploadedFile_fileName.lastIndexOf(".");
            if (index != -1)
                fileSuffix = _uploadedFile_fileName.substring(index + 1);
            if (fileSuffix != null && !fileSuffix.equals("")) {
                GenericValue gv = delegator.findOne("FileExtension", true, UtilMisc.toMap("fileExtensionId", fileSuffix.toLowerCase()));
                if (gv != null)
                    contentType = gv.getString("mimeTypeId");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (UtilValidate.isNotEmpty(imageDataBytes) && UtilValidate.isNotEmpty(_uploadedFile_fileName)) {
            InputStream input = new ByteArrayInputStream(imageDataBytes.array());
            // 创建OSSClient实例
            OSSClient client = getNewOssClient(delegator);
            ObjectMetadata objectMeta = new ObjectMetadata();
            objectMeta.setContentLength(imageDataBytes.array().length);
            objectMeta.setContentEncoding("UTF-8");

            // 可以在metadata中标记文件类型
            objectMeta.setContentType(contentType);
            //String key = UUID.randomUUID() + _uploadedFile_fileName;
            String OSSFileLocation = "others/";
            if (UtilValidate.isNotEmpty(context.get("fileLocation"))) {
                try {
                    Map resultMap = dispatcher.runSync("getCache", UtilMisc.toMap("cacheKey", context.get("fileLocation")));
                    if (UtilValidate.isEmpty(resultMap.get("result"))) {
                        GenericValue enumGv = delegator.findOne("Enumeration", false, UtilMisc.toMap("enumId", context.get("fileLocation")));
                        if (UtilValidate.isNotEmpty(enumGv) && UtilValidate.isNotEmpty(enumGv.get("enumCode"))) {
                            OSSFileLocation = enumGv.get("enumCode").toString();
                        }
                    } else {
                        OSSFileLocation = resultMap.get("result").toString();
                    }
                } catch (GenericServiceException e) {
                    // TODO Auto-generated catch block
                    Debug.logError(e.getMessage(), module);
                } catch (GenericEntityException e) {
                    // TODO Auto-generated catch block
                    Debug.logError(e.getMessage(), module);
                }
            }
            //上传key的时候，必须加上后缀，杀毒软件可能拦截
            String key = OSSFileLocation + UUID.randomUUID() + "." + fileSuffix;//fileSuffix==null?"":"."+fileSuffix;
            PutObjectResult pr = client.putObject(BUCKET_NAME, key, input, objectMeta);
            client.shutdown();
            result.put("fileName", key); 
            result.put("status", "Y");            
        }

        return result;

    }
	
	/**
	 * 删除oss上的文件
	 * @param key
	 */
	public static void delFile(String key){
		if(UtilValidate.isNotEmpty(key)){
			OSSClient client = new OSSClient(ENDPOINT,ACCESS_ID, ACCESS_KEY_SECRET);
			client.deleteObject(BUCKET_NAME, key);
		}
	}
	/**
	 * 根据配置文件名称及属性名称得到相应的属性值（路径）
	 * @param confFileName	配置文件名称
	 * @param attributeName	属性名称
	 * @return	属性值（路径）
	 */
	public static String getPath(String confFileName, String attributeName){
		String imageSite = null;
		String path = UtilProperties.getPropertyValue(confFileName, attributeName);
		try {
			URL url = FlexibleLocation.resolveLocation(path);
			imageSite = url.getPath();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
		
		return imageSite;
	}
}
