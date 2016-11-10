package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilNumber;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

public class CloudCardQueryServices {
	public static final String module = CloudCardQueryServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";
	public static int decimals = UtilNumber.getBigDecimalScale("finaccount.decimals");
    public static int rounding = UtilNumber.getBigDecimalRoundingMode("finaccount.rounding");
    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(decimals, rounding);
    
    /**
	 * 查询卡信息
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> findFinAccountByPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String partyId = (String) context.get("partyId");
		Integer viewIndex = (Integer) context.get("viewIndex");
		Integer viewSize = (Integer) context.get("viewSize");

		Map<String, Object> inputFieldMap = FastMap.newInstance();
		inputFieldMap.put("partyId", partyId);
		inputFieldMap.put("statusId", "FNACT_ACTIVE");

		Map<String, Object> ctxMap = FastMap.newInstance();
		ctxMap.put("inputFields", inputFieldMap);
		ctxMap.put("entityName", "FinAccountAndPaymentMethodAndGiftCard");
		ctxMap.put("orderBy", "expireDate");
		ctxMap.put("viewIndex", viewIndex);
		ctxMap.put("viewSize", viewSize);
		ctxMap.put("filterByDate", "Y");

		Map<String, Object> faResult = null;
		try {
			faResult = dispatcher.runSync("performFindList", ctxMap);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		List<GenericValue> retList = UtilGenerics.checkList(faResult.get("list"));
		List<Object> finaccountList = FastList.newInstance();

		//图片地址
		for(GenericValue finaccount:retList){
			Map<String, Object> finaccountMap = FastMap.newInstance();
			finaccountMap.putAll(finaccount);
			String organizationPartyId = finaccount.get("organizationPartyId").toString();
			if(organizationPartyId != null){
				finaccountMap.put("cardImg", EntityUtilProperties.getPropertyValue("cloudcard","cardImg."+organizationPartyId,delegator));
			}
			finaccountList.add(finaccountMap);
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("finAccountList", finaccountList);
		return result;
	}

	/**
	 * 查询交易流水
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> findPaymentByPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Locale locale = (Locale) context.get("locale");
		String partyIdFrom = (String) context.get("partyIdFrom");
		String partyIdTo = (String) context.get("partyIdTo");
		String paymentTypeId = (String) context.get("paymentTypeId");
		Integer viewIndex = (Integer) context.get("viewIndex");
		Integer viewSize = (Integer) context.get("viewSize");

		Map<String, Object> inputFieldMap = FastMap.newInstance();
		inputFieldMap.put("partyIdFrom", partyIdFrom);
		inputFieldMap.put("partyIdTo", partyIdTo);
		inputFieldMap.put("paymentTypeId", paymentTypeId);

		Map<String, Object> ctxMap = FastMap.newInstance();
		ctxMap.put("inputFields", inputFieldMap);
		ctxMap.put("entityName", "PaymentAndTypePartyNameView");
		ctxMap.put("orderBy", "effectiveDate");
		ctxMap.put("viewIndex", viewIndex);
		ctxMap.put("viewSize", viewSize);

		Map<String, Object> paymentResult = null;
		try {
			paymentResult = dispatcher.runSync("performFindList", ctxMap);
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("paymentList", paymentResult.get("list"));
		return result;
	}
	
	/**
	 * 查询卖卡余额和开卡额度
	 * @param dctx
	 * @param context
	 * @return Map
	 */

	public static Map<String, Object> findLimitAndPresellInfo(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");
		BigDecimal incrementTotal = ZERO; 

		String organizationPartyId = (String) context.get("organizationPartyId");
		// 获取商户金融账户
		GenericValue partyGroupFinAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(partyGroupFinAccount)) {
        	Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }
		//查询已卖卡金额
		EntityConditionList<EntityCondition> incrementConditions = EntityCondition.makeCondition(UtilMisc.toList(
				EntityCondition.makeCondition("finAccountId", EntityOperator.EQUALS,
						partyGroupFinAccount.get("finAccountId")),
				EntityCondition.makeCondition(
						EntityCondition.makeCondition("amount", EntityOperator.GREATER_THAN, ZERO))),
				EntityOperator.AND);
		
        List<GenericValue> finAccountAuthList = null;
		try {
			finAccountAuthList = delegator.findList("FinAccountAuth", incrementConditions, UtilMisc.toSet("amount"), null, null, false);
		} catch (GenericEntityException e) {
			e.printStackTrace();
		}
		
		//计算卖卡金额
		if (finAccountAuthList != null) {
			for (GenericValue finAccountAuth : finAccountAuthList) {
				incrementTotal = incrementTotal.add(BigDecimal.valueOf(UtilMisc.toDouble(finAccountAuth.get("amount"))));
			}
		}
        
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("presellAmount", incrementTotal);
		results.put("totalAmount", partyGroupFinAccount.get("actualBalance"));
		results.put("actualBalance", partyGroupFinAccount.get("availableBalance"));
		return results;
	}
	
	
	/**
	 * 查询卖卡余额和开卡额度
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	
	public static Map<String, Object> getCardInfoByCode(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
//		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		
		GenericValue cloudCardAccount;
		try {
			cloudCardAccount = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		
		Map<String, Object> results = ServiceUtil.returnSuccess();

		if(null != cloudCardAccount){
			results.put("isExist", "Y");
			BigDecimal actualBalance = cloudCardAccount.getBigDecimal("actualBalance");
			if(null==actualBalance){
				results.put("actualBalance", ZERO);
			}else{
				results.put("actualBalance", actualBalance);
			}
			String cardOrganizationPartyId = cloudCardAccount.get("organizationPartyId").toString();
			if(cardOrganizationPartyId != null){
				results.put("cardImg", EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + cardOrganizationPartyId, delegator));
			}
			results.put("finAccountId", cloudCardAccount.getString("finAccountId"));
			results.put("finAccountName", cloudCardAccount.getString("finAccountName"));
			results.put("customerPartyId", cloudCardAccount.getString("customerPartyId"));
		}else{
			results.put("isExist", "N");
		}
		
		return results;
	}
	
}
