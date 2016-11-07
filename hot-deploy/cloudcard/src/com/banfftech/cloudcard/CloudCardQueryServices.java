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
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

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

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("finAccountList", retList);
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
						EntityCondition.makeCondition("finAccountTransTypeId", EntityOperator.EQUALS, "WITHDRAWAL"))),
				EntityOperator.AND);
		
        List<GenericValue> transSums;
		try {
			transSums = delegator.findList("FinAccountTransSum", incrementConditions, UtilMisc.toSet("amount"), null, null, false);
	        incrementTotal = addFirstEntryAmount(incrementTotal, transSums, "amount", (decimals+1), rounding);
		} catch (GenericEntityException e) {
			e.printStackTrace();
		}
        
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("presellAmount", incrementTotal);
		results.put("totalAmount", partyGroupFinAccount.get("replenishLevel"));
		results.put("actualBalance", partyGroupFinAccount.get("actualBalance"));
		return results;
	}
	
	public static BigDecimal addFirstEntryAmount(BigDecimal initialValue, List<GenericValue> transactions, String fieldName, int decimals, int rounding) throws GenericEntityException {
        if ((transactions != null) && (transactions.size() == 1)) {
            GenericValue firstEntry = transactions.get(0);
            if (firstEntry.get(fieldName) != null) {
                BigDecimal valueToAdd = firstEntry.getBigDecimal(fieldName);
                return initialValue.add(valueToAdd).setScale(decimals, rounding);
            } else {
                return initialValue;
            }
        } else {
            return initialValue;
        }
   }
}