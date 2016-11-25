package com.banfftech.cloudcard.pay.alipay;


import java.io.PrintWriter;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.alibaba.fastjson.JSON;
import com.banfftech.cloudcard.pay.alipay.bean.AlipayNotification;
import com.banfftech.cloudcard.pay.alipay.util.AlipayNotify;
import com.banfftech.cloudcard.pay.alipay.util.RequestUtils;

public class AsynAlipayNotifyServices {

	/**
	 * 异步接受支付宝支付结果 支付宝服务器调用
	 * 
	 * @param request
	 * @param response
	 */

	public static void receiveNotify(HttpServletRequest request, HttpServletResponse response) {

		Map<String, String> underScoreKeyMap = RequestUtils.getStringParams(request);
		Map<String, String> camelCaseKeyMap = RequestUtils.convertKeyToCamelCase(underScoreKeyMap);

		// 首先验证调用是否来自支付宝
		boolean verifyResult = AlipayNotify.verify(underScoreKeyMap);

		try {

			String jsonString = JSON.toJSONString(camelCaseKeyMap);
			AlipayNotification notice = JSON.parseObject(jsonString, AlipayNotification.class);
			notice.setVerifyResult(verifyResult);

			String resultResponse = "success";
			PrintWriter printWriter = null;
			try {
				printWriter = response.getWriter();
				// do business
				if (verifyResult) {

				}
				// fail due to verification error
				else {
					resultResponse = "fail";
				}

			} catch (Exception e) {
				resultResponse = "fail";
				printWriter.close();
			}

			if (printWriter != null) {
				printWriter.print(resultResponse);
			}

		} catch (Exception e1) {

			e1.printStackTrace();
		}
	}
}
