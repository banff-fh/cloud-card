package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilFormatOut;
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
	 * 查询用户卡列表
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> myCloudCards(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = (String) userLogin.get("partyId");
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
		
		if(ServiceUtil.isError(faResult)){
			return faResult;
		}

		List<GenericValue> retList = UtilGenerics.checkList(faResult.get("list"));
		List<Object> cloudCardList = FastList.newInstance();
		
		//图片地址
		for(GenericValue cloudCard : retList){
			Map<String, Object> cloudCardMap = FastMap.newInstance();
//			cloudCardMap.putAll(cloudCard);
			String organizationPartyId = cloudCard.get("distributorPartyId").toString();
			if(organizationPartyId != null){
				cloudCardMap.put("cardImg", EntityUtilProperties.getPropertyValue("cloudcard","cardImg."+organizationPartyId,delegator));
			}
			String cardName = UtilFormatOut.checkEmpty(cloudCard.getString("description"), cloudCard.getString("finAccountName"));
			cloudCardMap.put("cardName", cardName); //卡名
			cloudCardMap.put("cardCode", cloudCard.get("cardNumber")); //卡二维码
			cloudCardMap.put("cardId", cloudCard.get("paymentMethodId"));// 卡id
			cloudCardMap.put("cardBalance", cloudCard.get("actualBalance")); //余额
			cloudCardMap.put("distributorPartyId", cloudCard.get("distributorPartyId")); //发卡商家partyId
			//卡主，如果此卡是别人授权给我用的，此字段就是原卡主
			cloudCardMap.put("ownerPartyId", cloudCard.get("ownerPartyId")); 
			cloudCardList.add(cloudCardMap);
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("cloudCardList", cloudCardList);
		result.put("listSize", faResult.get("listSize"));
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
	 * 根据二维码查询卡信息
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> getCardInfoByCode(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");
		
//		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		
		GenericValue cloudCard;
		try {
			cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		
		if(null == cloudCard){
			Debug.logInfo("找不到云卡，cardCode[" + cardCode + "]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardNotFound", locale)); 
		}
		
		String statusId = cloudCard.getString("statusId");
		if("FNACT_CANCELLED".equals(statusId) || "FNACT_MANFROZEN".equals(statusId)){
			Debug.logInfo("此卡[finAccountId=" + cloudCard.get("finAccountId") + "]状态不可用，当前状态[" + statusId +"]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardHasBeenDisabled", locale)); 
		}
		
		
		Map<String, Object> results = ServiceUtil.returnSuccess();
		if("FNACT_ACTIVE".equals(statusId)){
			results.put("isActivated", "Y");
		}else{
			results.put("isActivated", "N");
		}
		
		BigDecimal actualBalance = cloudCard.getBigDecimal("actualBalance");
		if(null==actualBalance){
			results.put("cardBalance", ZERO);
		}else{
			results.put("cardBalance", actualBalance);
		}
		String cardOrganizationPartyId = cloudCard.getString("distributorPartyId");
		if(cardOrganizationPartyId != null){
			results.put("cardImg", EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + cardOrganizationPartyId, delegator));
			results.put("distributorPartyId", cardOrganizationPartyId); //发卡商家partyId
		}
		String cardName = UtilFormatOut.checkEmpty(cloudCard.getString("description"), cloudCard.getString("finAccountName"));
		results.put("cardName", cardName); //卡名
		results.put("cardCode", cardCode); //卡二维码
		results.put("cardId", cloudCard.get("paymentMethodId"));// 卡id
		results.put("customerPartyId", cloudCard.getString("partyId"));
		//卡主，如果此卡是别人授权给我用的，此字段就是原卡主
		results.put("ownerPartyId", cloudCard.get("ownerPartyId")); 
		
		return results;
	}
	
	/**
	 * 新卡导出excel
	 * @param request
	 * @param response
	 * @return
	 */
	/*public static String outCardNumberExcel(HttpServletRequest request,HttpServletResponse response){
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		String ownerPartyId = request.getParameter("ownerPartyId");
		Delegator delegator =  dispatcher.getDelegator();
		Map<String,Object> context = UtilHttp.getParameterMap(request);
		GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
		Locale locale = (Locale) context.get("locale");
		context.put("userLogin", userLogin);
		context.put("ownerPartyId",ownerPartyId);
		context.put("searchLx","excel");

		Map<String,Object> results=null;
		try {
			Map<String, Object> inputFieldMap = FastMap.newInstance();
			inputFieldMap.put("statusId", "FNACT_CREATED");

			Map<String, Object> ctxMap = FastMap.newInstance();
			ctxMap.put("inputFields", inputFieldMap);
			ctxMap.put("entityName", "FinAccount");
			ctxMap.put("orderBy", "expireDate");
			ctxMap.put("filterByDate", "Y");

			Map<String, Object> faResult = null;
			try {
				faResult = dispatcher.runSync("performFindList", ctxMap);
			} catch (GenericServiceException e) {
				Debug.logError(e.getMessage(), module);
			}

			List<Map<Object,Object>> list = (List<Map<Object, Object>>) faResult.get("list");
						

			HSSFWorkbook wb = new HSSFWorkbook();
			HSSFSheet sheet = wb.createSheet("卡云卡");
			HSSFRow row1 = sheet.createRow(0);
			HSSFCell cell0 = row1.createCell((short)0);
			HSSFCell cell1 = row1.createCell((short)1);
			HSSFCell cell2 = row1.createCell((short)2);
			cell1.setCellValue("卡名");
			cell1.setCellValue("卡号");
			if(UtilValidate.isNotEmpty(list)){
				for(int i=0;i<list.size();i++){
					HSSFRow row = sheet.createRow(i+1);
					HSSFCell ce0 = row.createCell((short)0);
					Map<Object,Object> mm = list.get(i);
					ce0.setCellValue(String.valueOf(mm.get("finAccountName")));
					HSSFCell ce1 = row.createCell((short)1);
					ce1.setCellValue(String.valueOf(mm.get("finAccountCode")));
					
				}
				   response.reset();
				   response.addHeader("Content-Disposition", "attachment;filename=" + new String("交易明细.xls".getBytes("gb2312"), "ISO8859-1"));
				   response.setContentType("application/msexcel;charset=utf-8");
				   OutputStream toClient = response.getOutputStream();
				   wb.write(toClient);
				toClient.flush();
	            toClient.close();
			}
		}  catch (IOException e) {
			e.printStackTrace();
		}
		return "SUCCESS";
	}*/
}
