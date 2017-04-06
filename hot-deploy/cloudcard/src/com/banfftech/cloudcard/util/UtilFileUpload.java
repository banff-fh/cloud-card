package com.banfftech.cloudcard.util;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
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
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.LocalDispatcher;

import com.aliyun.oss.OSSClient;
import com.aliyun.oss.model.ObjectMetadata;

import javolution.util.FastMap;

public class UtilFileUpload {
	public static String module = UtilFileUpload.class.getName();
	private static String ENDPOINT = "";
	private static String ACCESS_ID = "";
	private static String ACCESS_KEY_SECRET = "";
	private static String BUCKET_NAME = "";
	/**
	 * 上传文件，并返回图片路径及图片名称
	 * @param imageFolder
	 * @param request
	 * @return
	 * @throws Exception
	 */
	public static Map<String, Object> uploadFile(HttpServletRequest request) throws Exception {
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		Delegator delegator = dispatcher.getDelegator();
		ENDPOINT = EntityUtilProperties.getPropertyValue("cloudcard","oss.endpoint",delegator);
		ACCESS_ID = EntityUtilProperties.getPropertyValue("cloudcard","oss.accessKeyId",delegator);
		ACCESS_KEY_SECRET = EntityUtilProperties.getPropertyValue("cloudcard","oss.accessKeySecret",delegator);
		BUCKET_NAME = EntityUtilProperties.getPropertyValue("cloudcard","oss.bucketName",delegator);

		Map<String, Object> context = FastMap.newInstance();
		FileItemFactory factory = new DiskFileItemFactory();
		ServletFileUpload upload = new ServletFileUpload(factory);
		try {
			//获取文件项List 
			@SuppressWarnings("unchecked")
			List<FileItem> list = upload.parseRequest(request);
			//建立文件项的迭代器
			Iterator<FileItem> it = list.iterator();
			while (it.hasNext()) {
				FileItem fileItem = (FileItem) it.next();
				//判断是否为表单域，false则是file文件 
				if (fileItem.isFormField()) {
					//取文件的各个属lla
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
						OSSClient client = new OSSClient(ENDPOINT,ACCESS_ID, ACCESS_KEY_SECRET);
				        ObjectMetadata objectMeta = new ObjectMetadata();
				        objectMeta.setContentLength(fileItem.getSize());
				        // 可以在metadata中标记文件类型
				        objectMeta.setContentType("image/jpeg");
				        String key = UUID.randomUUID()+fileItem.getName();
				        InputStream input = fileItem.getInputStream();
				        client.putObject(BUCKET_NAME, key, input, objectMeta);
						context.put("fileName", key);
						context.put("filePath", key);
					}
				}
			}
		} catch (Exception e) {
			Debug.logError(e.getMessage(),module);
		}
		return context;
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
