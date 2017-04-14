package com.banfftech.cloudcard;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilFormatOut;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.util.EntityFindOptions;
import org.ofbiz.entity.util.EntityListIterator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.util.CloudCardInfoUtil;

import javolution.util.FastList;
import javolution.util.FastMap;

public class CloudCardQueryServices {
	public static final String module = CloudCardQueryServices.class.getName();

    /**
     * 查询用户卡列表
     * 
     * @param dctx
     * @param context
     * @return Map
     */
    public static Map<String, Object> myCloudCards(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        String storeId = (String) context.get("storeId");
        String partyId = (String) context.get("partyId");
        if(UtilValidate.isEmpty(partyId)){
        	GenericValue userLogin = (GenericValue) context.get("userLogin");
            partyId = (String) userLogin.get("partyId");
        }
        
        Integer viewIndex = (Integer) context.get("viewIndex");
        Integer viewSize = (Integer) context.get("viewSize");
        viewIndex = (viewIndex == null || viewIndex < 0) ? 0 : viewIndex;
        viewSize = (viewSize == null || viewSize == 0) ? 20 : viewSize;

        int start = viewIndex.intValue() * viewSize.intValue();
        int maxRows = viewSize.intValue() * (viewIndex.intValue() + 1);
        int listSize = 0;

        List<GenericValue> retList = FastList.newInstance();
        EntityListIterator listIt = null;
        try {
            EntityCondition lookupConditions = CloudCardInfoUtil.createLookupMyStoreCardCondition(delegator, partyId, storeId);
            listIt = delegator.find("CloudCardInfo", lookupConditions, null, null, UtilMisc.toList("-fromDate"),
                    new EntityFindOptions(true, EntityFindOptions.TYPE_SCROLL_INSENSITIVE, EntityFindOptions.CONCUR_READ_ONLY, -1, maxRows, false));
            listSize = listIt.getResultsSizeAfterPartialList();
            retList = listIt.getPartialList(start + 1, viewSize);

        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        } finally {
            if (null != listIt) {
                try {
                    listIt.close();
                } catch (GenericEntityException e) {
                    Debug.logError(e.getMessage(), module);
                }
            }
        }

        List<Object> cloudCardList = FastList.newInstance();

        for (GenericValue cloudCard : retList) {
            Map<String, Object> cloudCardMap = CloudCardInfoUtil.packageCloudCardInfo(delegator, cloudCard);
            cloudCardList.add(cloudCardMap);
        }

        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("cloudCardList", cloudCardList);
        result.put("listSize", listSize);
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
		String cardId = (String) context.get("cardId");
		
		// 分页相关
        Integer viewIndex =  (Integer) context.get("viewIndex");
        Integer viewSize  = (Integer) context.get("viewSize");
		
        EntityCondition paymentConditions = null;
        

		/*Timestamp fromDate =(Timestamp)context.get("fromTime");
        fromDate = UtilDateTime.getMonthStart(fromDate,0);

		Timestamp thruDate =(Timestamp)context.get("thruTime");
		thruDate =  UtilDateTime.getDayStart(thruDate, 1);
		
        EntityCondition timeConditions = EntityCondition.makeCondition("effectiveDate", EntityOperator.BETWEEN, UtilMisc.toList(fromDate, thruDate));*/
        
        if("1".equals(type)){
        	/*EntityCondition depositConditions = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_DEPOSIT", "partyIdTo", partyId));
        	paymentConditions = EntityCondition.makeCondition(EntityOperator.AND, depositConditions, timeConditions);*/
        	
        	paymentConditions = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_DEPOSIT", "partyIdTo", partyId));


		}else if("2".equals(type)){
			/*EntityCondition withDrawalCondition = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_WITHDRAWAL", "partyIdFrom", partyId));
        	paymentConditions = EntityCondition.makeCondition(EntityOperator.AND, withDrawalCondition, timeConditions);*/
        	
			paymentConditions = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_WITHDRAWAL", "partyIdFrom", partyId));


		}else{
			/*EntityCondition depositCond = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_DEPOSIT", "partyIdTo", partyId));
	        EntityCondition withDrawalCond = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_WITHDRAWAL", "partyIdFrom", partyId));
			EntityCondition allConditions = EntityCondition.makeCondition(EntityOperator.OR, depositCond, withDrawalCond);
	        paymentConditions = EntityCondition.makeCondition(EntityOperator.AND, allConditions, timeConditions);*/
	        
	        EntityCondition depositCond = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_DEPOSIT", "partyIdTo", partyId));
	        EntityCondition withDrawalCond = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_WITHDRAWAL", "partyIdFrom", partyId));
	        paymentConditions = EntityCondition.makeCondition(EntityOperator.OR, depositCond, withDrawalCond);
		}

        if(UtilValidate.isNotEmpty(cardId)){
        	paymentConditions = EntityCondition.makeCondition(paymentConditions, EntityCondition.makeCondition("paymentMethodId", cardId));
        }

        //每页显示条数
        int number =  (viewSize  == null || viewSize  == 0) ? 20 : viewSize ;
        // 每页的开始记录 第一页为1 第二页为number +1
        int lowIndex = viewIndex * number + 1;  
        //总页数
        int totalPage = 0;
        int listSize = 0;
        EntityListIterator eli  = null;
		try {
			eli = delegator.find("PaymentAndTypePartyNameView", paymentConditions, null, UtilMisc.toSet("amount","partyFromGroupName","partyToGroupName","paymentTypeId","effectiveDate"), UtilMisc.toList("-effectiveDate"), null);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		List<GenericValue> payments = FastList.newInstance();
		try {
			payments = eli.getPartialList(lowIndex, number);
            eli.last();
            listSize = eli.getResultsSizeAfterPartialList();
			totalPage = listSize % number == 0 ? listSize/number : (listSize/number)+1;
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}finally {  
            try {  
                if (eli != null) {  
                    eli.close();  
                    eli = null;  
                }  
            } catch (GenericEntityException e) {  
                Debug.logError(e.getMessage(), module);  
            }  
        }
		Map<String, Object> paymentsMap = FastMap.newInstance();
		
		List<Map<String,Object>> paymentsList = FastList.newInstance();
		int oldYear = 2000;
		int oldMonth = 01;
		boolean isNew = false;
		List<Object> yearAndMonthPaymentList = FastList.newInstance();
		for(GenericValue payment : payments){
			Map<String, Object> paymentMap = FastMap.newInstance();
			paymentMap.put("amount", payment.get("amount"));
			paymentMap.put("transDate", UtilDateTime.toCalendar(payment.getTimestamp("effectiveDate")).getTimeInMillis());
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
			
			int year = UtilDateTime.getYear(payment.getTimestamp("effectiveDate"), TimeZone.getTimeZone("GMT+:08:00"), locale);
			int month = UtilDateTime.getMonth(payment.getTimestamp("effectiveDate"), TimeZone.getTimeZone("GMT+:08:00"), locale) + 1;
			if(!isNew){
				oldYear = year;
				oldMonth = month;
				isNew = true;
			}
			if(oldYear == year && oldMonth == month){
				paymentsMap.put("dateTime", String.valueOf(year) +"年"+ String.valueOf(month)+"月");
				paymentsMap.put("paymentsList", paymentsList);
				if(!yearAndMonthPaymentList.contains(paymentsMap)){
					yearAndMonthPaymentList.add(0, paymentsMap);
				}else{
					yearAndMonthPaymentList.set(0, paymentsMap);
				}
				
			}else{
				paymentsList.clear();
				paymentsMap.clear();
				paymentsMap.put("dateTime", String.valueOf(year) +"年"+ String.valueOf(month)+"月");
				paymentsMap.put("paymentsList", paymentsList);
				yearAndMonthPaymentList.set(0, paymentsMap);
			}
			oldYear = year;
			oldMonth = month;
		}
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("yearAndMonthPaymentList", yearAndMonthPaymentList);
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
		if(!CloudCardHelper.isManager(delegator, partyId, organizationPartyId)){
			Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能进行账户流水查询操作", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}

		String type = (String) context.get("type");
		// 分页相关
        Integer viewIndex =  (Integer) context.get("viewIndex");
        Integer viewSize  = (Integer) context.get("viewSize");

        EntityCondition paymentConditions = null;
        
		Timestamp fromDate =(Timestamp)context.get("fromTime");
        fromDate = UtilDateTime.adjustTimestamp(fromDate, Calendar.SECOND, -2);

		Timestamp thruDate =(Timestamp)context.get("thruTime");
		thruDate =  UtilDateTime.getDayStart(thruDate, 1);
		
        EntityCondition timeConditions = EntityCondition.makeCondition("effectiveDate", EntityOperator.BETWEEN, UtilMisc.toList(fromDate, thruDate));
        
        if("1".equals(type)){
        	EntityCondition  depositConditions =  EntityCondition.makeCondition(EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_DEPOSIT", "partyIdFrom", organizationPartyId)));
        	paymentConditions = EntityCondition.makeCondition(EntityOperator.AND, depositConditions, timeConditions);
			
        }else if("2".equals(type)){
        	
        	EntityCondition withDrawalConditions = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_WITHDRAWAL", "partyIdTo", organizationPartyId));
        	paymentConditions = EntityCondition.makeCondition(EntityOperator.AND, withDrawalConditions, timeConditions);

		}else{
			EntityCondition depositCondition = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_DEPOSIT", "partyIdFrom", organizationPartyId));
			EntityCondition withDrawalCondition = EntityCondition.makeCondition(UtilMisc.toMap("paymentTypeId", "GC_WITHDRAWAL", "partyIdTo", organizationPartyId));
			EntityCondition allConditions = EntityCondition.makeCondition(EntityOperator.OR, depositCondition, withDrawalCondition);
			paymentConditions = EntityCondition.makeCondition(EntityOperator.AND, allConditions, timeConditions);
		}

        

        //每页显示条数
        int number =  (viewSize  == null || viewSize  == 0) ? 20 : viewSize ;
        // 每页的开始记录 第一页为1 第二页为number +1
        int lowIndex = viewIndex * number + 1;  
        //总页数
        int totalPage = 0;
        int listSize = 0;
        EntityListIterator eli  = null;
		try {
			eli = delegator.find("PaymentAndTypePartyNameView", paymentConditions, null, UtilMisc.toSet("amount","partyToFirstName","partyFromFirstName","paymentTypeId","effectiveDate"), UtilMisc.toList("-effectiveDate"), null);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		List<GenericValue> payments = null;
		try {
			payments = eli.getPartialList(lowIndex, number);
            eli.last();
			listSize = eli.getResultsSizeAfterPartialList();
			totalPage = listSize % number == 0 ? listSize/number : (listSize/number)+1;
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}finally {  
            try {  
                if (eli != null) {  
                    eli.close();  
                    eli = null;  
                }  
            } catch (GenericEntityException e) {  
                Debug.logError(e.getMessage(), module);  
            }  
        }
		
		List<Map<String, Object>> paymentsList = FastList.newInstance();
		for(GenericValue payment : payments){
			Map<String, Object> paymentMap = FastMap.newInstance();
			paymentMap.put("amount", payment.get("amount"));
			paymentMap.put("transDate", payment.getTimestamp("effectiveDate").toString());
			if("GC_DEPOSIT".equals(payment.getString("paymentTypeId"))){
				//partyToLastName partyToFirstName
				paymentMap.put("customerName", payment.get("partyToFirstName"));
				paymentMap.put("typeDesc", "充值");
				paymentMap.put("type", "1");
				paymentsList.add(paymentMap);
			}else if ("GC_WITHDRAWAL".equals(payment.getString("paymentTypeId"))){
				//partyFromLastName  partyFromFirstName
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
        	return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
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
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
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
        	return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }
		BigDecimal settlementAmount = partySettlementFinAccount.getBigDecimal("actualBalance");
		if(null == settlementAmount){
			settlementAmount = CloudCardHelper.ZERO;
		}
		BigDecimal limitAmount =  partyGroupFinAccount.getBigDecimal("replenishLevel");
		if(null == limitAmount){
			limitAmount = CloudCardHelper.ZERO;
		}
		BigDecimal balance = partyGroupFinAccount.getBigDecimal("availableBalance");
		if(null == balance){
			balance = CloudCardHelper.ZERO;
		}
        
		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("presellAmount", presellAmount);
		results.put("limitAmount", limitAmount);
		results.put("balance", balance);
		results.put("liabilities", limitAmount.subtract(balance));
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
			cloudCard = CloudCardHelper.getCloudCardByCardCode(cardCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale)); 
		}
		
		if(null == cloudCard){
			Debug.logInfo("找不到云卡，cardCode[" + cardCode + "]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotFound", locale)); 
		}
		
		String statusId = cloudCard.getString("statusId");
		if("FNACT_CANCELLED".equals(statusId) || "FNACT_MANFROZEN".equals(statusId)){
			Debug.logInfo("此卡[finAccountId=" + cloudCard.get("finAccountId") + "]状态不可用，当前状态[" + statusId +"]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardHasBeenDisabled", locale)); 
		}
		
		
		Map<String, Object> results = ServiceUtil.returnSuccess();
		boolean isActivated = "FNACT_ACTIVE".equals(statusId);
		results.put("isActivated", CloudCardHelper.bool2YN(isActivated));
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
				GenericValue cloudCard = CloudCardHelper.getCloudCardByCardCode(cardCode, false, delegator);
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
			ctxMap.put("entityName", "CloudCardInfo");
			

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
			HSSFSheet sheet = wb.createSheet("库胖卡");
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
						if("FNACT_CREATED".equalsIgnoreCase(mm.getString("statusId"))){
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
                response.addHeader("Content-Disposition",
                        "attachment;filename=" + new String(("库胖卡_" + UtilDateTime.nowAsString() + ".xls").getBytes("utf-8"), "ISO8859-1"));
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
	 * @deprecated 不需要这个接口，由各app商店负责更新
	 */
	public static Map<String, Object> checkAppVersion(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		String curVersion = (String) context.get("curVersion");
		String deviceType = (String) context.get("deviceType");// ios android
		String appType = (String) context.get("appType"); // biz user
		int updateType = 0;
		String version = EntityUtilProperties.getPropertyValue("cloudcard","app."+deviceType+"."+appType+".version",delegator);
		String isUpdate = EntityUtilProperties.getPropertyValue("cloudcard","app."+deviceType+"."+appType+".update",delegator);
		String url = "http://fir.im/6nzd";

		String latestVersion = curVersion;
		if(!version.equals(curVersion)){
			latestVersion = version;
			updateType = Integer.valueOf(isUpdate);
		}

		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("latestVersion", latestVersion);
		results.put("updateType", updateType);
		results.put("url", url);
		return results;
	}

	
	/**
	 * 根据条码获取商家信息
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getStoreInfoByQRcode(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String qrCode = (String) context.get("qrCode");

		// 简单校验传入的 二维码是否合法
		boolean isValid = qrCode.startsWith(CloudCardConstant.STORE_QR_CODE_PREFIX);
		if(isValid){
			try{
				UUID.fromString(qrCode.substring(CloudCardConstant.STORE_QR_CODE_PREFIX.length()));
			}catch(Exception e){
				isValid = false;
			}
		}

		if(!isValid){
			Debug.logError("invalide store qrCode[" + qrCode + "]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardStoreQRcodeInvalid", locale));
		}

		GenericValue partyGroup;
		try {
			partyGroup = CloudCardHelper.getPartyGroupByQRcode(qrCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(null == partyGroup){
			Debug.logInfo("colud not find the PartyGroup by qrCode[" + qrCode + "]", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardStoreQRcodeInvalid", locale));
		}

		String storeName = partyGroup.getString("groupName");
		String storeId = partyGroup.getString("partyId");
//		String storeImgUrl = partyGroup.getString("logoImageUrl");
		String storeImgUrl = EntityUtilProperties.getPropertyValue("cloudcard","cardImg." + storeId,delegator);

		Map<String, Object> results = ServiceUtil.returnSuccess();
		results.put("storeId", storeId);
		results.put("storeName", storeName);
		results.put("storeImgUrl", storeImgUrl);
		return results;
	}

}
