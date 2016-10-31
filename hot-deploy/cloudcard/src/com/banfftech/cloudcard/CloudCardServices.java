package com.banfftech.cloudcard;

import java.util.List;
import java.util.Map;

import org.ofbiz.base.conversion.DateTimeConverters;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastMap;
import java.sql.Timestamp;

/**
 * @author subenkun
 *
 */
public class CloudCardServices {
	/**
	 * 查询卡信息
	 */
	public static Map<String, Object> findFinAccountByOwnerPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		String ownerPartyId = (String) context.get("ownerPartyId");
		Integer viewIndex = (Integer) context.get("viewIndex");
		Integer viewSize = (Integer) context.get("viewSize");

		Map<String, Object> inputFieldMap = FastMap.newInstance();
		inputFieldMap.put("ownerPartyId", ownerPartyId);
		inputFieldMap.put("statusId", "FNACT_ACTIVE");

		Map<String, Object> ctxMap = FastMap.newInstance();
		ctxMap.put("inputFields", inputFieldMap);
		ctxMap.put("entityName", "FinAccount");
		ctxMap.put("orderBy", "finAccountId");
		ctxMap.put("viewIndex", viewIndex);
		ctxMap.put("viewSize", viewSize);

		Map<String, Object> faResult = null;
		try {
			faResult = dispatcher.runSync("performFindList", ctxMap);
		} catch (GenericServiceException e) {
			e.printStackTrace();
		}

		List<GenericValue> retList = UtilGenerics.checkList(faResult.get("list"));

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("finAccountList", retList);
		return result;
	}

	/**
	 * 查询交易流水
	 */

	public static Map<String, Object> findPaymentByPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
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
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("paymentList", paymentResult.get("list"));
		return result;
	}

	/**
	 * 卡授权
	 */
	public static Map<String, Object> createCardAuth(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Map<String, Object> result = null;
		String telNumber = (String) context.get("telNumber");
		String finAccountId = (String) context.get("finAccountId");
		String amount = (String) context.get("amount");
		Timestamp fromDate = (Timestamp) context.get("fromDate");
		Timestamp thruDate = (Timestamp) context.get("thruDate");

		try {
			// 授权时判断用户是否存在
			GenericValue person = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", telNumber));
			// 如果用户不存在，该用户设置为新用户
			if (person == null) {
				Map<String, Object> personAndUserLoginMap = FastMap.newInstance();
				personAndUserLoginMap.put("userLoginId", (String) context.get("telNumber"));
				personAndUserLoginMap.put("currentPassword", context.get("currentPassword"));
				personAndUserLoginMap.put("currentPasswordVerify", context.get("currentPasswordVerify"));
				personAndUserLoginMap.put("enabled", "Y");
				dispatcher.runSync("createPersonAndUserLogin", personAndUserLoginMap);
			}
						
			
		} catch (GenericServiceException | GenericEntityException e) {
			// TODO Auto-generated catch block
			result = ServiceUtil.returnError("create failed");
			e.printStackTrace();
		}

		result = ServiceUtil.returnSuccess();
		return result;
	}

}
