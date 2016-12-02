package com.banfftech.cloudcard;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityConditionList;
import org.ofbiz.entity.condition.EntityExpr;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityListIterator;
import org.ofbiz.entity.util.EntityUtil;
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
		ctxMap.put("orderBy", "-fromDate");
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
		
		for(GenericValue cloudCard : retList){
			Map<String, Object> cloudCardMap = FastMap.newInstance();
//			cloudCardMap.putAll(cloudCard);
			String organizationPartyId = cloudCard.get("distributorPartyId").toString();
			if(organizationPartyId != null){
				//图片地址
				cloudCardMap.put("cardImg", EntityUtilProperties.getPropertyValue("cloudcard","cardImg."+organizationPartyId,delegator));
			}
			String cardName = UtilFormatOut.checkEmpty(cloudCard.getString("description"), cloudCard.getString("finAccountName"));
			String authThruDate="";
			cloudCardMap.put("cardName", cardName); //卡名
			String cardCode = cloudCard.getString("cardNumber");
			cloudCardMap.put("cardCode", cardCode); //卡二维码
			cloudCardMap.put("cardId", cloudCard.get("paymentMethodId"));// 卡id
			
			boolean isAuthorized = false;
			if(cardCode.startsWith(CloudCardHelper.AUTH_CARD_CODE_PREFIX)){
				// 如果是别人授权给我的卡，显示授权金额的余额
				cloudCardMap.put("isAuthToMe", "Y"); // 已授权给我
				cloudCardMap.put("isAuthToOthers", "N"); // 已授权给别人
				cloudCardMap.put("authFromDate", cloudCard.getTimestamp("fromDate").toString()); // 授权开始时间
				if(UtilValidate.isNotEmpty(cloudCard.getTimestamp("thruDate"))){
					authThruDate = cloudCard.getTimestamp("thruDate").toString();
				}
				cloudCardMap.put("authThruDate", authThruDate); // 授权结束时间
				cloudCardMap.put("authFromPartyId", cloudCard.get("ownerPartyId")); // 谁授权
				cloudCardMap.put("authToPartyId", partyId); // 授权给谁
			}else{
				//账户可用余额
				// 如果是已经授权给别人的卡，展示授权开始、结束时间，以及授权给谁
				Map<String, Object> cardAuthorizeInfo = CloudCardHelper.getCardAuthorizeInfo(cloudCard, delegator);
				isAuthorized = (boolean) cardAuthorizeInfo.get("isAuthorized");
				if(isAuthorized){
					cloudCardMap.put("isAuthToMe", "N"); // 已授权给我
					cloudCardMap.put("isAuthToOthers", "Y"); // 已授权给别人
					cloudCardMap.put("authFromDate", ((Timestamp)cardAuthorizeInfo.get("fromDate")).toString()); // 授权开始时间
					if(UtilValidate.isNotEmpty(cardAuthorizeInfo.get("thruDate"))){
						authThruDate = ((Timestamp)cardAuthorizeInfo.get("thruDate")).toString();
					}
					cloudCardMap.put("authThruDate", authThruDate); // 授权结束时间
					cloudCardMap.put("authFromPartyId", partyId); // 谁授权
					cloudCardMap.put("authToPartyId", cardAuthorizeInfo.get("toPartyId")); // 授权给谁
				}else{
					cloudCardMap.put("isAuthToMe", "N"); // 已授权给我
					cloudCardMap.put("isAuthToOthers", "N"); // 已授权给别人
				}
			}
			cloudCardMap.put("cardBalance", CloudCardHelper.getCloudCardBalance(cloudCard, isAuthorized));
			cloudCardMap.put("distributorPartyId", cloudCard.get("distributorPartyId")); //发卡商家partyId
			// 卡主
			cloudCardMap.put("ownerPartyId", cloudCard.get("ownerPartyId")); 
			cloudCardList.add(cloudCardMap);
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("cloudCardList", cloudCardList);
		result.put("listSize", faResult.get("listSize"));
		return result;
	}

	/**
	 * 查询用户交易流水
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> getUserPayment(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = (String) userLogin.get("partyId");
		String type = (String) context.get("type");
		// 分页相关
        Integer viewIndex =  (Integer) context.get("viewIndex");
        Integer viewSize  = (Integer) context.get("viewSize");

		List<EntityExpr> depositExprs = FastList.newInstance();
        List<EntityExpr> withDrawalExprs = FastList.newInstance();
        EntityConditionList<EntityCondition> paymentConditions = null;
        
        if("1".equals(type)){
	        depositExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_DEPOSIT"));
	        depositExprs.add(EntityCondition.makeCondition("partyIdTo", EntityOperator.EQUALS,partyId));
	        EntityCondition depositCondition = EntityCondition.makeCondition(depositExprs, EntityOperator.AND);
	        paymentConditions = EntityCondition.makeCondition(depositCondition);
		}else if("2".equals(type)){
			withDrawalExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_WITHDRAWAL"));
	        withDrawalExprs.add(EntityCondition.makeCondition("partyIdFrom", EntityOperator.EQUALS,partyId));
	        EntityCondition depositCondition = EntityCondition.makeCondition(withDrawalExprs, EntityOperator.AND);
	        paymentConditions = EntityCondition.makeCondition(depositCondition);
		}else{
			depositExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_DEPOSIT"));
	        depositExprs.add(EntityCondition.makeCondition("partyIdTo", EntityOperator.EQUALS,partyId));
	        withDrawalExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_WITHDRAWAL"));
	        withDrawalExprs.add(EntityCondition.makeCondition("partyIdFrom", EntityOperator.EQUALS,partyId));
	        EntityCondition depositCondition = EntityCondition.makeCondition(depositExprs, EntityOperator.AND);
	        EntityCondition withDrawalCondition = EntityCondition.makeCondition(withDrawalExprs, EntityOperator.AND);
	        paymentConditions = EntityCondition.makeCondition(UtilMisc.toList(depositCondition,withDrawalCondition),EntityOperator.OR);
		}
		    	
        //每页显示条数
        int number =  (viewSize  == null || viewSize  == 0) ? 20 : viewSize ;
        // 每页的开始记录 第一页为1 第二页为number +1
        int lowIndex = viewIndex * number + 1;  
        //总页数
        int totalPage = 0;
        EntityListIterator eli  = null;
		try {
			eli = delegator.find("PaymentAndTypePartyNameView", paymentConditions, null, UtilMisc.toSet("amount","partyFromGroupName","partyToGroupName","paymentTypeId","effectiveDate"), UtilMisc.toList("-effectiveDate"), null);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		List<GenericValue> payments = FastList.newInstance();
		try {
			payments = eli.getPartialList(lowIndex, number);
            eli.last();  
			totalPage = (eli.getResultsSizeAfterPartialList()%number == 0 ? eli.getResultsSizeAfterPartialList()/number:(eli.getResultsSizeAfterPartialList()/number)+1);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}finally {  
            try {  
                if (eli != null) {  
                    eli.close();  
                    eli = null;  
                }  
            } catch (GenericEntityException e) {  
                Debug.logError(e.getMessage().toString(), module);  
            }  
        }
		List<Map<String, Object>> paymentsList = FastList.newInstance();
		for(GenericValue payment : payments){
			Map<String, Object> paymentMap = FastMap.newInstance();
			paymentMap.put("amount", payment.get("amount"));
			paymentMap.put("transDate", payment.getTimestamp("effectiveDate").toString());
			if("GC_DEPOSIT".equals(payment.getString("paymentTypeId"))){
				paymentMap.put("storeName", payment.get("partyFromGroupName"));
				paymentMap.put("typeDesc", "充值");
				paymentMap.put("type", "1");
				paymentsList.add(paymentMap);
			}else if ("GC_WITHDRAWAL".equals(payment.getString("paymentTypeId"))){
				paymentMap.put("storeName", payment.get("partyToGroupName"));
				paymentMap.put("typeDesc", "支付");
				paymentMap.put("type", "2");
				paymentsList.add(paymentMap);
			}
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("paymentList", paymentsList);
		result.put("totalPage", totalPage);

		return result;
	}
	
	/**
	 * 查询商铺交易流水
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> getBizPayment(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String partyId = (String) userLogin.get("partyId");
		String organizationPartyId = (String) context.get("organizationPartyId");
		if(UtilValidate.isEmpty(CloudCardHelper.getOrganizationPartyId(delegator, partyId))){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能进行账户流水查询操作", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserLoginIsNotManager", locale));
		}
//		organizationPartyId = CloudCardHelper.getOrganizationPartyId(delegator, partyId).get(0);

		String type = (String) context.get("type");
		// 分页相关
        Integer viewIndex =  (Integer) context.get("viewIndex");
        Integer viewSize  = (Integer) context.get("viewSize");

		List<EntityExpr> depositExprs = FastList.newInstance();
        List<EntityExpr> withDrawalExprs = FastList.newInstance();
        EntityConditionList<EntityCondition> paymentConditions = null;
        
        if("1".equals(type)){
	        depositExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_DEPOSIT"));
	        depositExprs.add(EntityCondition.makeCondition("partyIdFrom", EntityOperator.EQUALS,organizationPartyId));
	        EntityCondition depositCondition = EntityCondition.makeCondition(depositExprs, EntityOperator.AND);
	        paymentConditions = EntityCondition.makeCondition(depositCondition);
		}else if("2".equals(type)){
			withDrawalExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_WITHDRAWAL"));
	        withDrawalExprs.add(EntityCondition.makeCondition("partyIdTo", EntityOperator.EQUALS,organizationPartyId));
	        EntityCondition depositCondition = EntityCondition.makeCondition(withDrawalExprs, EntityOperator.AND);
	        paymentConditions = EntityCondition.makeCondition(depositCondition);
		}else{
			depositExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_DEPOSIT"));
	        depositExprs.add(EntityCondition.makeCondition("partyIdFrom", EntityOperator.EQUALS,organizationPartyId));
	        withDrawalExprs.add(EntityCondition.makeCondition("paymentTypeId", EntityOperator.EQUALS,"GC_WITHDRAWAL"));
	        withDrawalExprs.add(EntityCondition.makeCondition("partyIdTo", EntityOperator.EQUALS,organizationPartyId));
	        EntityCondition depositCondition = EntityCondition.makeCondition(depositExprs, EntityOperator.AND);
	        EntityCondition withDrawalCondition = EntityCondition.makeCondition(withDrawalExprs, EntityOperator.AND);
	        paymentConditions = EntityCondition.makeCondition(UtilMisc.toList(depositCondition,withDrawalCondition),EntityOperator.OR);
		}
		    	
        //每页显示条数
        int number =  (viewSize  == null || viewSize  == 0) ? 20 : viewSize ;
        // 每页的开始记录 第一页为1 第二页为number +1
        int lowIndex = viewIndex * number + 1;  
        //总页数
        int totalPage = 0;
        EntityListIterator eli  = null;
		try {
			eli = delegator.find("PaymentAndTypePartyNameView", paymentConditions, null, UtilMisc.toSet("amount","partyToFirstName","partyFromFirstName","paymentTypeId","effectiveDate"), UtilMisc.toList("-effectiveDate"), null);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		List<GenericValue> payments = null;
		try {
			payments = eli.getPartialList(lowIndex, number);
            eli.last();  
			totalPage = (eli.getResultsSizeAfterPartialList()%number == 0 ? eli.getResultsSizeAfterPartialList()/number:(eli.getResultsSizeAfterPartialList()/number)+1);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}finally {  
            try {  
                if (eli != null) {  
                    eli.close();  
                    eli = null;  
                }  
            } catch (GenericEntityException e) {  
                Debug.logError(e.getMessage().toString(), module);  
            }  
        }
		
		List<Map<String, Object>> paymentsList = FastList.newInstance();
		for(GenericValue payment : payments){
			Map<String, Object> paymentMap = FastMap.newInstance();
			paymentMap.put("amount", payment.get("amount"));
			paymentMap.put("transDate", payment.getTimestamp("effectiveDate").toString());
			if("GC_DEPOSIT".equals(payment.getString("paymentTypeId"))){
				paymentMap.put("customerName", payment.get("partyToFirstName"));
				paymentMap.put("typeDesc", "充值");
				paymentMap.put("type", "1");
				paymentsList.add(paymentMap);
			}else if ("GC_WITHDRAWAL".equals(payment.getString("paymentTypeId"))){
				paymentMap.put("customerName", payment.get("partyFromFirstName"));
				paymentMap.put("typeDesc", "支付");
				paymentMap.put("type", "2");
				paymentsList.add(paymentMap);
			}
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("paymentList", paymentsList);
		result.put("totalPage", totalPage);

		return result;
		
	}
	
	/**
	 * 查询卖卡余额和开卡额度
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> getLimitAndPresellInfo(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");
		BigDecimal presellAmount = CloudCardHelper.ZERO; 

		String organizationPartyId = (String) context.get("organizationPartyId");
		// 获取商户卖卡额度金融账户
		GenericValue partyGroupFinAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(partyGroupFinAccount)) {
        	Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }
		//查询已卖卡金额
		EntityCondition incrementConditions = EntityCondition.makeCondition(
						EntityCondition.makeCondition("finAccountId", EntityOperator.EQUALS, partyGroupFinAccount.get("finAccountId")),
						EntityCondition.makeCondition("amount", EntityOperator.GREATER_THAN, CloudCardHelper.ZERO),
						EntityUtil.getFilterByDateExpr()
				);
		
        List<GenericValue> finAccountAuthList = null;
		try {
			finAccountAuthList = delegator.findList("FinAccountAuth", incrementConditions, UtilMisc.toSet("amount"), null, null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		//计算卖卡金额
		if (finAccountAuthList != null) {
			for (GenericValue finAccountAuth : finAccountAuthList) {
				presellAmount = presellAmount.add(BigDecimal.valueOf(UtilMisc.toDouble(finAccountAuth.get("amount"))));
			}
		}
		
		GenericValue partySettlementFinAccount = CloudCardHelper.getSettlementAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(partySettlementFinAccount)) {
        	Debug.logError("商家[" + organizationPartyId + "]未配置平台结算账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }
		BigDecimal settlementAmount = partySettlementFinAccount.getBigDecimal("actualBalance");
		if(null == settlementAmount){
			settlementAmount = CloudCardHelper.ZERO;
		}
        
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("presellAmount", presellAmount);
		results.put("limitAmount", partyGroupFinAccount.get("replenishLevel"));
		results.put("balance", partyGroupFinAccount.get("availableBalance"));
		// 账户余额本身表示应付给平台金额，店家应该看到的是应从平台收取
		results.put("settlementAmount", settlementAmount.negate());
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
		
		results.put("cardBalance", CloudCardHelper.getCloudCardBalance(cloudCard));

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
	public static String exportCardExcel(HttpServletRequest request,HttpServletResponse response){
		LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
		String distributorPartyId = request.getParameter("distributorPartyId");
		/*if(null == distributorPartyId){
			distributorPartyId = "";
		}*/
		String finAccountName = request.getParameter("finAccountName");
		String finAccountName_op = request.getParameter("finAccountName_op");
		String finAccountId = request.getParameter("finAccountId");
		String statusId = request.getParameter("statusId");
		String cardCode = request.getParameter("cardCode");
		String ownerPartyId = request.getParameter("ownerPartyId");
		String partyId = request.getParameter("partyId");
		String filterByDate = request.getParameter("filterByDate");
		
		Delegator delegator =  dispatcher.getDelegator();
		Map<String,Object> context = UtilHttp.getParameterMap(request);
		GenericValue userLogin = (GenericValue) request.getSession().getAttribute("userLogin");
		Locale locale = (Locale) context.get("locale");
		

		try {
			Map<String, Object> ctxMap = FastMap.newInstance();
			ctxMap.put("noConditionFind", "Y");
			Map<String, Object> inputFieldMap = FastMap.newInstance();
			// 有可能是“null” 这样的字符串。。。
			if(UtilValidate.isNotEmpty(cardCode) && !"null".equals(cardCode)){
				GenericValue cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, false, delegator);
				if(null!=cloudCard){
					inputFieldMap.put("finAccountId", cloudCard.getString("finAccountId"));
				}else{
					ctxMap.put("noConditionFind", "N");
				}
			}
			if(UtilValidate.isNotEmpty(distributorPartyId) && !"null".equals(distributorPartyId)){
				inputFieldMap.put("distributorPartyId", distributorPartyId);
			}
			if(UtilValidate.isNotEmpty(finAccountName) && !"null".equals(finAccountName)){
				inputFieldMap.put("finAccountName", finAccountName);
			}
			if(UtilValidate.isNotEmpty(finAccountName_op) && !"null".equals(finAccountName_op)){
				inputFieldMap.put("finAccountName_op", finAccountName_op);
			}
			if(UtilValidate.isNotEmpty(finAccountId) && !"null".equals(finAccountId)){
				inputFieldMap.put("finAccountId", finAccountId);
			}
			if(UtilValidate.isNotEmpty(partyId) && !"null".equals(partyId)){
				inputFieldMap.put("partyId", partyId);
			}
			if(UtilValidate.isNotEmpty(ownerPartyId) && !"null".equals(ownerPartyId)){
				inputFieldMap.put("ownerPartyId", ownerPartyId);
			}
			if(UtilValidate.isNotEmpty(statusId) && !"null".equals(statusId)){
				inputFieldMap.put("statusId", statusId);
			}
			if(UtilValidate.isNotEmpty(filterByDate) && !"null".equals(filterByDate)){
				inputFieldMap.put("filterByDate", filterByDate);
			}

			ctxMap.put("inputFields", inputFieldMap);
			ctxMap.put("userLogin", userLogin);
			ctxMap.put("entityName", "FinAccountAndPaymentMethodAndGiftCard");
			

			Map<String, Object> faResult = null;
			try {
				faResult = dispatcher.runSync("performFind", ctxMap);
			} catch (GenericServiceException e) {
				Debug.logError(e.getMessage(), module);
			}
			
			List<GenericValue> list = FastList.newInstance();
			Integer listSize = (Integer) faResult.get("listSize");
			if(null == listSize || listSize < 1){
				return "SUCCESS";
			}
			EntityListIterator it = (EntityListIterator) faResult.get("listIt");
			list = it.getCompleteList();
			it.close();

			HSSFWorkbook wb = new HSSFWorkbook();
			HSSFSheet sheet = wb.createSheet("卡云卡");
			HSSFRow row1 = sheet.createRow(0);
			HSSFCell cell0 = row1.createCell((short) 0);
			HSSFCell cell1 = row1.createCell((short) 1);
			cell0.setCellValue("卡名");
			cell1.setCellValue("卡号");
			List<GenericValue> finAccounts = FastList.newInstance();
			if (UtilValidate.isNotEmpty(list)) {
				for (int i = 0; i < list.size(); i++) {
					HSSFRow row = sheet.createRow(i + 1);
					HSSFCell ce0 = row.createCell((short) 0);
					GenericValue mm = list.get(i);
					ce0.setCellValue(String.valueOf(mm.get("finAccountName")));
					HSSFCell ce1 = row.createCell((short) 1);
					ce1.setCellValue(String.valueOf(mm.get("finAccountCode")));
					try {
						//导出后更新finaccount状态
						if(mm.get("statusId").toString().equalsIgnoreCase("FNACT_CREATED") ){
							GenericValue finAccount = delegator.findByPrimaryKey("FinAccount",UtilMisc.toMap("finAccountId", mm.get("finAccountId")) );
							finAccount.put("statusId", "FNACT_PUBLISHED");
							finAccounts.add(finAccount);
						}
					} catch (GenericEntityException e) {
						Debug.logError(e.getMessage(), module);
					}
				}
				delegator.storeAll(finAccounts);
				response.reset();
				response.addHeader("Content-Disposition", "attachment;filename=" + new String("卡云卡生成的卡号.xls".getBytes("utf-8"), "ISO8859-1"));
				response.setContentType("application/msexcel;charset=utf-8");
				OutputStream out = response.getOutputStream();
				wb.write(out);
				wb.close();
				out.flush();
				out.close();
			}
		} catch (GenericEntityException | IOException e) {
			Debug.logError(e.getMessage(), module,locale);
		}
		return "SUCCESS";
	}
	
	/**
	 * 查询app版本信息
	 * @param dctx
	 * @param context
	 * @return Map
	 */
	public static Map<String, Object> checkAppVersion(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		String curVersion = (String) context.get("curVersion");
		String deviceType = (String) context.get("deviceType");
		String appType = (String) context.get("appType");
		String latestVersion = null;
		int updateType = 0;
		String version = EntityUtilProperties.getPropertyValue("cloudcard","app."+deviceType+"."+appType+".version",delegator);
		String isUpdate = EntityUtilProperties.getPropertyValue("cloudcard","app."+deviceType+"."+appType+".update",delegator);
		String url = "http://fir.im/6nzd";
		//判断appType是商家或用户
		if("biz".equals(appType)){
			//判断当前设备是ios或android
			if("ios".equals(deviceType)){
				if(!version.equals(curVersion)){
					latestVersion = version;
					updateType = Integer.valueOf(isUpdate);
				}else{
					latestVersion = curVersion;
				}
			}else if("android".equals(deviceType)){
				if(!version.equals(curVersion)){
					latestVersion = version;
					updateType = Integer.valueOf(isUpdate);
				}else{
					latestVersion = curVersion;
				}
			}
		}else if("user".equals(appType)){
			//判断当前设备是ios或android
			if("ios".equals(deviceType)){
				if(!version.equals(curVersion)){
					latestVersion = version;
					updateType = Integer.valueOf(isUpdate);
				}else{
					latestVersion = curVersion;
				}
			}else if("android".equals(deviceType)){
				if(!version.equals(curVersion)){
					latestVersion = version;
					updateType = Integer.valueOf(isUpdate);
				}else{
					latestVersion = curVersion;
				}
			}
		}
		
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("latestVersion", latestVersion);
		results.put("updateType", updateType);
		results.put("url", url);
		return results;
	}

}
