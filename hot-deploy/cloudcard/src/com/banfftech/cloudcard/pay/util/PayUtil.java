package com.banfftech.cloudcard.pay.util;

import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.util.EntityUtilProperties;

import com.alibaba.fastjson.JSONObject;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.domain.AlipayFundTransOrderQueryModel;
import com.alipay.api.domain.AlipayFundTransToaccountTransferModel;
import com.alipay.api.request.AlipayFundTransOrderQueryRequest;
import com.alipay.api.request.AlipayFundTransToaccountTransferRequest;
import com.alipay.api.response.AlipayFundTransOrderQueryResponse;
import com.alipay.api.response.AlipayFundTransToaccountTransferResponse;

public class PayUtil {
	private static String URL = "https://openapi.alipay.com/gateway.do";
	private static String APP_KUPANG_ID = "";
	private static String APP_KUPANG_TRANSFER_PRIVATE_KEY = "";
	private static String APP_KUPANG_TRANSFER_PUBLIC_KEY = "";
	private static String FORMAT = "JSON";
	private static String CHARSET = "utf-8";
	private static String SIGN_TYPE = "RSA";

	public static void getSmsProperty(Delegator delegator){
		URL = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.url","https://openapi.alipay.com/gateway.do",delegator);
		APP_KUPANG_ID = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangAppID",delegator);
		APP_KUPANG_TRANSFER_PRIVATE_KEY = EntityUtilProperties.getPropertyValue("cloudcard.properties", "aliPay.rsa_kupang_transfer_private",delegator);
		APP_KUPANG_TRANSFER_PUBLIC_KEY = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.kupangPublicKey",delegator);
		SIGN_TYPE = EntityUtilProperties.getPropertyValue("cloudcard","aliPay.signType","RSA",delegator);
	}

	/**
	 * 买卡或充值转账到商户个人账户
	 */
	public static boolean transfer(Delegator delegator,Map<String,Object> transferMap) throws AlipayApiException {
		AlipayFundTransToaccountTransferModel model = new AlipayFundTransToaccountTransferModel();;
		model.setOutBizNo(transferMap.get("orderId").toString());// 生成订单号
		model.setPayeeType("ALIPAY_LOGONID");// 固定值
		model.setPayeeAccount(transferMap.get("payeeAccount").toString());// 转账收款账户
		model.setAmount(transferMap.get("totalAmount").toString());//转账金额
		model.setPayerRealName(transferMap.get("payerRealName").toString());// 账户真实名称
		model.setPayeeRealName(transferMap.get("payeeRealName").toString()); //收款方真是姓名
		model.setRemark(transferMap.get("remark").toString());

		//获取支付应用初始值
		getSmsProperty(delegator);
		//开始转账
		AlipayFundTransToaccountTransferResponse response = transferToResponse(model);
		String result = response.getBody();
		//转账信息
		Debug.logInfo("单笔转账到个人账户转账信息：", result);
		if (response.isSuccess()) {
			return true;
		} else {
			// 调用查询接口查询数据
			JSONObject jsonObject = JSONObject.parseObject(result);
			String out_biz_no = jsonObject.getJSONObject("alipay_fund_trans_toaccount_transfer_response")
					.getString("out_biz_no");
			AlipayFundTransOrderQueryModel queryModel = new AlipayFundTransOrderQueryModel();
			model.setOutBizNo(out_biz_no);
			boolean isSuccess = transferQuery(queryModel);
			if (isSuccess) {
				return true;
			}
		}
		return false;
	}

	public static AlipayFundTransToaccountTransferResponse transferToResponse(
			AlipayFundTransToaccountTransferModel model) throws AlipayApiException {
		AlipayClient alipayClient = new DefaultAlipayClient(URL, APP_KUPANG_ID, APP_KUPANG_TRANSFER_PRIVATE_KEY, FORMAT, CHARSET,
				APP_KUPANG_TRANSFER_PUBLIC_KEY, SIGN_TYPE);
		AlipayFundTransToaccountTransferRequest request = new AlipayFundTransToaccountTransferRequest();
		request.setBizModel(model);
		return alipayClient.execute(request);
	}

	/**
	 * 转账查询接口
	 *
	 * @param content
	 * @return
	 * @throws AlipayApiException
	 */
	public static boolean transferQuery(AlipayFundTransOrderQueryModel model) throws AlipayApiException {
		AlipayFundTransOrderQueryResponse response = transferQueryToResponse(model);
		Debug.logInfo("单笔转账到个人账户查询结果：",  response.getBody());
		if (response.isSuccess()) {
			return true;
		}
		return false;
	}

	public static AlipayFundTransOrderQueryResponse transferQueryToResponse(AlipayFundTransOrderQueryModel model)
			throws AlipayApiException {
		AlipayClient alipayClient = new DefaultAlipayClient(URL, APP_KUPANG_ID, APP_KUPANG_TRANSFER_PRIVATE_KEY, FORMAT, CHARSET,
				APP_KUPANG_TRANSFER_PUBLIC_KEY, SIGN_TYPE);
		AlipayFundTransOrderQueryRequest request = new AlipayFundTransOrderQueryRequest();
		request.setBizModel(model);
		return alipayClient.execute(request);
	}
}
