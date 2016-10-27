package com.banfftech.finaccount;

import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.entity.Delegator;
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
public class FinAccountServices {
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

	public static Map<String, Object> findFinAccountTransByPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		String partyId = (String) context.get("partyId");
		Integer viewIndex = (Integer) context.get("viewIndex");
		Integer viewSize = (Integer) context.get("viewSize");

		Map<String, Object> inputFieldMap = FastMap.newInstance();
		inputFieldMap.put("partyId", partyId);

		Map<String, Object> ctxMap = FastMap.newInstance();
		ctxMap.put("inputFields", inputFieldMap);
		ctxMap.put("entityName", "FinAccountTrans");
		ctxMap.put("orderBy", "transactionDate");
		ctxMap.put("viewIndex", viewIndex);
		ctxMap.put("viewSize", viewSize);

		Map<String, Object> finAccountTransResult = null;
		try {
			finAccountTransResult = dispatcher.runSync("performFindList", ctxMap);
		} catch (GenericServiceException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("finAccountTransList", finAccountTransResult.get("list"));
		return result;
	}

}
