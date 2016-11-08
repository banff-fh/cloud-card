package com.banfftech.cloudcard.sms;

import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardQueryServices;
import com.taobao.api.ApiException;
import com.taobao.api.DefaultTaobaoClient;
import com.taobao.api.TaobaoClient;
import com.taobao.api.request.AlibabaAliqinFcSmsNumSendRequest;
import com.taobao.api.response.AlibabaAliqinFcSmsNumSendResponse;

public class SmsServices {
	public static final String module = CloudCardQueryServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";
	private static String url = null;
	private static String appkey = null;
	private static String secret = null;
	private static String smsFreeSignName = null;
	private static String smsTemplateCode = null;
	
	public static void getSmsProperty(Delegator delegator){
		url = EntityUtilProperties.getPropertyValue("cloudcard","sms.url",delegator);
		appkey = EntityUtilProperties.getPropertyValue("cloudcard","sms.appkey",delegator);
		secret = EntityUtilProperties.getPropertyValue("cloudcard","sms.secret",delegator);
		smsFreeSignName = EntityUtilProperties.getPropertyValue("cloudcard","sms.smsFreeSignName",delegator);
		smsTemplateCode = EntityUtilProperties.getPropertyValue("cloudcard","sms.smsTemplateCode",delegator);
	}

	public static Map<String, Object> sendMessage(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String phone = (String) context.get("phone");
		String code = (String) context.get("code");
		String product = (String) context.get("product");
		//初始化短信发送配置文件
		getSmsProperty(delegator);
		//发送短信
		TaobaoClient client = new DefaultTaobaoClient(url, appkey, secret);
		AlibabaAliqinFcSmsNumSendRequest req = new AlibabaAliqinFcSmsNumSendRequest();
		req.setExtend("");
		req.setSmsType("normal");
		req.setSmsFreeSignName(smsFreeSignName);
		req.setSmsParamString("{code:'"+code+"',product:'"+product+"'}");
		req.setRecNum(phone);
		req.setSmsTemplateCode(smsTemplateCode);
		AlibabaAliqinFcSmsNumSendResponse rsp = null;
		try {
			rsp = client.execute(req);
		} catch (ApiException e) {
			Debug.logError(phone+"短信发送异常", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardSendMessageServiceError", UtilMisc.toMap("phone", phone),locale));
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}
}
