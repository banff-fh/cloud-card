package com.banfftech.cloudcard.pay;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.pay.alipay.AliPayServices;
import com.banfftech.cloudcard.pay.tenpay.WeiXinPayServices;

import javolution.util.FastMap;


public class PayServices {

    public static final String module = PayServices.class.getName();

	//微信支付宝统一下单
	public static Map<String, Object> uniformOrder(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String cardId = (String) context.get("cardId");
        String totalFee = (String) context.get("totalFee");
        String paymentType = (String) context.get("paymentType");
        BigDecimal amount = new BigDecimal(totalFee).setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);
        GenericValue cloudCard = null;
        GenericValue systemUserLogin = null;
        try {
            cloudCard = CloudCardHelper.getCloudCardByCardId(cardId, delegator);
            systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (null == cloudCard) {
            Debug.logWarning("找不到云卡，cardId[" + cardId + "]", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotFound", locale));
        }

        String storeId = cloudCard.getString("distributorPartyId");

        try {
            Map<String, Object> rechargeCloudCardReceiptOutMap = dispatcher.runSync("rechargeCloudCardReceipt", UtilMisc.toMap("userLogin", systemUserLogin,
                    "locale", locale, "cloudCard", cloudCard, "amount", amount, "organizationPartyId", storeId, "payChannel", paymentType));

            if (!ServiceUtil.isSuccess(rechargeCloudCardReceiptOutMap)) {
                return rechargeCloudCardReceiptOutMap;
            }

            String receiptPaymentId = (String) rechargeCloudCardReceiptOutMap.get("receiptPaymentId");
            context.put("receiptPaymentId", receiptPaymentId);
            context.put("storeId", storeId);
        } catch (GenericServiceException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
	    
		Map<String, Object> result = FastMap.newInstance();
		if(CloudCardConstant.PAY_CHANNEL_WXPAY.equals(paymentType)){
			result = WeiXinPayServices.prepayOrder(dctx, context);
		}else if(CloudCardConstant.PAY_CHANNEL_ALIPAY.equals(paymentType)){
			result = AliPayServices.prepayOrder(dctx, context);
		}
		return result;
	}
	
	//支付宝支付通知
	public static void aliPayNotify(HttpServletRequest request,HttpServletResponse response){
		AliPayServices.aliPayNotify(request, response);
	}
	
	//微信支付通知
	public static void wxPayNotify(HttpServletRequest request,HttpServletResponse response){
		WeiXinPayServices.wxPayNotify(request, response);
	}
	
	/**
	 * 支付订单查询
	 */
	public static Map<String, Object> orderPayQuery(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		String paymentType = (String) context.get("paymentType");
		Map<String, Object> orderMap = FastMap.newInstance();
		if ("aliPay".equals(paymentType)) {
			orderMap = AliPayServices.orderPayQuery(delegator, context);
		} else if ("wxPay".equals(paymentType)) {
			orderMap = WeiXinPayServices.orderPayQuery(delegator, context);
		}
		return orderMap;
	}

}
