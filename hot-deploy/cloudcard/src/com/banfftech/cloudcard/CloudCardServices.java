package com.banfftech.cloudcard;

import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import javolution.util.FastMap;

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

	public static Map<String, Object> findPaymentByPartyIdTo(DispatchContext dctx, Map<String, Object> context) {
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

}
