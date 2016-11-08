package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.util.EntityUtil;
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
	
	public static final String module = CloudCardServices.class.getName();
	
	public static final String DEFAULT_CURRENCY_UOM_ID = "CNY";
	
	public static final String resourceError = "cloudcardErrorUiLabels";
	public static final String resourceAccountingError = "AccountingErrorUiLabels";
	
	/**
	 * 卡授权
	 */
	public static Map<String, Object> createCardAuth(DispatchContext dctx, Map<String, Object> context) {
//		LocalDispatcher dispatcher = dctx.getDispatcher();
//		Delegator delegator = dispatcher.getDelegator();
//		Locale locale = (Locale) context.get("locale");
		String finAccountId = (String) context.get("finAccountId");
//		String amount = (String) context.get("amount");//TODO, 授权金额还未处理
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		GenericValue finAccount = (GenericValue) checkParamOut.get("finAccount");

		String cardCode = finAccount.getString("finAccountCode");
		
		// 授权时判断用户是否存在， 不存在则创建用户
		Map<String, Object> getOrCreateCustomerOut = CloudCardHelper.getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(getOrCreateCustomerOut)) {
			return getOrCreateCustomerOut;
		}		
		String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");
		
		//创建paymentMethod和giftCard
		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("cardNumber", cardCode);
		giftCardInMap.put("description", "将账户" + finAccountId + "授权给" + customerPartyId);
		giftCardInMap.put("customerPartyId", customerPartyId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}		
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("customerPartyId", customerPartyId);
		return result;
	}

	
	/**
	 *	开卡、充值统一服务,手机接口调用
	 *   1、扣商户开卡余额
	 *   2、存入用户账户
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> createCloudCardAndRecharge(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		String telNumber = (String) context.get("telNumber");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		
		
		//1、根据二维码获取卡信息
		GenericValue cloudCard;
		String finAccountId;
		String customerPartyId;
		try {
			cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		if(null == cloudCard ){
			// 没有激活的卡，调用开卡服务
			if(UtilValidate.isEmpty(telNumber)){
				Debug.logInfo("激活新卡需要输入客户手机号码", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCard", locale)); 
			}else{
				Map<String, Object> createCloudCardOutMap;
				try {
					createCloudCardOutMap = dispatcher.runSync("createCloudCard",
							UtilMisc.toMap("userLogin", userLogin, "locale",locale,
									"organizationPartyId", organizationPartyId, 
									"telNumber", telNumber,
									"cardCode", cardCode));
				} catch (GenericServiceException e1) {
					Debug.logError(e1, module);
					return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
				}
				if (ServiceUtil.isError(createCloudCardOutMap)) {
					return createCloudCardOutMap;
				}
				
				finAccountId = (String) createCloudCardOutMap.get("finAccountId");
				customerPartyId = (String) createCloudCardOutMap.get("customerPartyId");
			}
		}else{
			finAccountId = cloudCard.getString("finAccountId");
			// ownerPartyId 如果卡已授权给别人，partyId是被授权人，ownerPartyId是原主人
			customerPartyId = cloudCard.getString("partyId");
		}
		
		//2、充值
		Map<String, Object> rechargeCloudCardOutMap;
		try {
			rechargeCloudCardOutMap = dispatcher.runSync("rechargeCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"organizationPartyId", organizationPartyId, 
							"finAccountId", finAccountId,
							"amount", amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(rechargeCloudCardOutMap)) {
			return rechargeCloudCardOutMap;
		}
		
		
		//3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("actualBalance", rechargeCloudCardOutMap.get("actualBalance"));
		result.put("customerPartyId", customerPartyId);
		result.put("finAccountId", finAccountId);
		return result;
	}
	
	
	/**
	 * 给用户开卡服务
	 * 	1、根据telNumber查找用户，不存在则创建
	 *	2、创建FinAccount，关联卡上二维码等信息，并创建PaymentMethod
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> createCloudCard(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        GenericValue userLogin = (GenericValue) context.get("userLogin");
        Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String cardCode = (String) context.get("cardCode");
		
		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}

		GenericValue partyGroup = (GenericValue) checkParamOut.get("partyGroup");
		String finAccountName = partyGroup.get("groupName")+" 的卡";//TODO
		

		// 检查此二维码关联的卡是否存在
		GenericValue cloudCard;
		try {
			cloudCard = CloudCardHelper.getCloudCardAccountFromCode(cardCode, delegator);
		} catch (GenericEntityException e2) {
			Debug.logError(e2.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale)); 
		}
		if(null != cloudCard){
			Debug.logInfo("此卡[" + cardCode + "]在系统中已经存在", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardAlreadyExist", locale)); 
		}

		// 1、根据telNumber查找用户，不存在则创建 
		context.put("ensureCustomerRelationship", true);
		Map<String, Object>	 getOrCreateCustomerOut  = CloudCardHelper.getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(getOrCreateCustomerOut)) {
			return getOrCreateCustomerOut;
		}		
		String customerPartyId = (String) getOrCreateCustomerOut.get("customerPartyId");
		String customerUserLoginId = (String) getOrCreateCustomerOut.get("customerUserLoginId");
		
		
		// 2、创建finAccount 和 paymentMethod
		// finAccount
		Map<String, Object> finAccountMap = FastMap.newInstance();
		finAccountMap.put("userLogin", userLogin);
		finAccountMap.put("locale", locale);
		finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT"); //TODO 类型待定
		finAccountMap.put("finAccountName", finAccountName); 
		finAccountMap.put("finAccountCode", cardCode);
		finAccountMap.put("currencyUomId", DEFAULT_CURRENCY_UOM_ID);
		finAccountMap.put("organizationPartyId", organizationPartyId);
		finAccountMap.put("postToGlAccountId", "213200");// TODO 213500?
		finAccountMap.put("ownerPartyId", customerPartyId);
		
		Map<String, Object> finAccountOutMap;
		try {
			finAccountOutMap = dispatcher.runSync("createFinAccount", finAccountMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountOutMap)) {
			return finAccountOutMap;
		}
		String finAccountId = (String) finAccountOutMap.get("finAccountId");

		// 给卡主 OWNER 角色
		Map<String, Object> finAccountRoleOutMap;
		try {
			finAccountRoleOutMap = dispatcher.runSync("createFinAccountRole", 
					UtilMisc.toMap("userLogin", userLogin, "locale", locale, "finAccountId", finAccountId, "partyId", customerPartyId, "roleTypeId","OWNER"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountRoleOutMap)) {
			return finAccountRoleOutMap;
		}

		// 创建PaymentMethod  GiftCard
		Map<String, Object> giftCardInMap = FastMap.newInstance();
		giftCardInMap.putAll(context);
		giftCardInMap.put("cardNumber", cardCode);
		giftCardInMap.put("description", finAccountName);
		giftCardInMap.put("customerPartyId", customerPartyId);
		giftCardInMap.put("finAccountId", finAccountId);
		Map<String, Object> giftCardOutMap = CloudCardHelper.createPaymentMethodAndGiftCard(dctx, giftCardInMap);
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}	
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}

		// 3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("customerPartyId", customerPartyId);
		result.put("customerUserLoginId", customerUserLoginId);
		result.put("finAccountId", finAccountId);
		result.put("paymentMethodId",  giftCardOutMap.get("paymentMethodId"));
		return result;
	}

	
	/**
	 *	充值服务
	 *   1、扣商户开卡余额
	 *   2、存入用户账户
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> rechargeCloudCard(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String finAccountId = (String) context.get("finAccountId");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		// TODO，数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logError("userLogin: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能进行开卡、充值操作", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardUserLoginIsNotManager", locale));
		}

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
//		GenericValue partyGroup = (GenericValue) checkParamOut.get("partyGroup");
		GenericValue finAccount = (GenericValue) checkParamOut.get("finAccount");

		// 1、扣商户开卡余额
		// 获取商户金融账户
		GenericValue partyGroupFinAccount = CloudCardHelper.getCreditLimitAccount(delegator, organizationPartyId);
        if (UtilValidate.isEmpty(partyGroupFinAccount)) {
        	Debug.logError("商家[" + organizationPartyId + "]未配置卖卡额度账户", module);
        	return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
        }

		String partyGroupFinAccountId = (String) partyGroupFinAccount.get("finAccountId");

		// 检查开卡余额是否够用
		BigDecimal actualBalance = partyGroupFinAccount.getBigDecimal("actualBalance");
		if(null==actualBalance){
			actualBalance = BigDecimal.ZERO;
		}
		if (actualBalance.compareTo(amount) < 0) {
			Debug.logInfo("商户卖卡额度不足, 余额：" + actualBalance.toPlainString(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCreditIsNotEnough", UtilMisc.toMap("balance", actualBalance.toPlainString()), locale));
		}
		


		// 商户账户扣款（扣减开卡余额）
		Map<String, Object> finAccountWithdrawalOutMap;
		try {
			finAccountWithdrawalOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "statusId", "PMNT_SENT", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "WITHDRAWAL", 
							"paymentTypeId","COMMISSION_PAYMENT",// TODO paymentType待定 
							"finAccountId", partyGroupFinAccountId,
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", organizationPartyId, "partyIdTo",
							finAccount.get("ownerPartyId"), "amount", amount, "comments", "充值扣减卖卡额度",
							"reasonEnumId", "FATR_REPLENISH"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountWithdrawalOutMap)) {
			return finAccountWithdrawalOutMap;
		}
		
//		String withdrawalPaymentId = (String) finAccountWithdrawalOutMap.get("paymentId");
		

		// 用户账户存入
		Map<String, Object> finAccountDepositOutMap;
		try {
			finAccountDepositOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "statusId", "PMNT_RECEIVED", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "DEPOSIT", 
							"paymentTypeId","GC_DEPOSIT",// paymentType待定
							"finAccountId", finAccountId,
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", organizationPartyId, "partyIdTo",
							finAccount.get("ownerPartyId"), "amount", amount, "comments", "充值",
							"reasonEnumId", "FATR_REPLENISH"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountDepositOutMap)) {
			return finAccountDepositOutMap;
		}
		
//		String depositPaymentId = (String) finAccountDepositOutMap.get("paymentId");
		
		try {
			finAccount.refresh();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("actualBalance", finAccount.get("actualBalance"));
		result.put("customerPartyId", finAccount.get("ownerPartyId"));
		result.put("finAccountId", finAccountId);
		return result;
	}
	

	
	/**
	 *	云卡支付服务
	 *   1、扣除用户卡内余额
	 *   2、创建发票
	 *   3、应用支付与发票
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> cloudCardWithdraw(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");
		
		String organizationPartyId = (String) context.get("organizationPartyId");
		String finAccountId = (String) context.get("finAccountId");
		BigDecimal amount = (BigDecimal) context.get("amount");

		Map<String, Object> checkParamOut = checkInputParam(dctx, context);
		if(ServiceUtil.isError(checkParamOut)){
			return checkParamOut;
		}
		GenericValue finAccount = (GenericValue) checkParamOut.get("finAccount");

		
		String customerPartyId = finAccount.getString("ownerPartyId");
		
		//通过organizationPartyId查找productStoreId
		GenericValue productStore;
		try {
			productStore = EntityUtil.getFirst( delegator.findByAnd("ProductStore", UtilMisc.toMap("payToPartyId", organizationPartyId), UtilMisc.toList("-" + ModelEntity.STAMP_FIELD)));
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		
		if(productStore==null){
			Debug.logError("商家[" + organizationPartyId + "]未配置ProductStore", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardConfigError", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
		}

		
		// 1、扣除用户余额

		// 检查开卡余额是否够用
		BigDecimal actualBalance = finAccount.getBigDecimal("actualBalance");
		if(null==actualBalance){
			actualBalance = BigDecimal.ZERO;
		}
		if (actualBalance.compareTo(amount) < 0) {
			Debug.logError("余额不足, 余额：" + actualBalance.toPlainString(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardBalanceIsNotEnough", 
					UtilMisc.toMap("finAccountId", finAccountId, "balance", actualBalance.toPlainString()), locale));
		}
		
		
		String productStoreId = productStore.getString("productStoreId");
		Map<String, Object> finAccountWithdrawalOutMap;
		try {
			finAccountWithdrawalOutMap = dispatcher.runSync("finAccountWithdraw",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"finAccountId", finAccountId, 
							"productStoreId", productStoreId,
							"currency", DEFAULT_CURRENCY_UOM_ID, 
							"partyId", customerPartyId,
							"reasonEnumId","FATR_PURCHASE",
//							"requireBalance","",
							"amount",amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(finAccountWithdrawalOutMap)) {
			return finAccountWithdrawalOutMap;
		}
		
		boolean processResult =  (boolean) finAccountWithdrawalOutMap.get("processResult");
		//TODO  这个referenceNum其实是 finAccountTransId String referenceNum =  (String) finAccountWithdrawalOutMap.get("referenceNum");
		if(!processResult){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardPaymentFailure", locale));
		}
		
		
		// 2、创建发票及发票明细
		Map<String, Object> invoiceOutMap;
		String description = customerPartyId +"在"+organizationPartyId+"消费"+amount.toPlainString();
		try {
			invoiceOutMap = dispatcher.runSync("createInvoice",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"statusId", "INVOICE_IN_PROCESS", 
							"invoiceTypeId", "SALES_INVOICE",
							"currencyUomId", DEFAULT_CURRENCY_UOM_ID, 
							"description", description,
							"partyId", customerPartyId,
							"partyIdFrom", organizationPartyId));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(invoiceOutMap)) {
			return invoiceOutMap;
		}
		String invoiceId = (String) invoiceOutMap.get("invoiceId");
		
		Map<String, Object> invoiceItemOutMap;
		try {
			invoiceItemOutMap = dispatcher.runSync("createInvoiceItem",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"invoiceId",invoiceId,
							"description", description,
							"invoiceItemTypeId", "INV_PROD_ITEM",
							"quantity", BigDecimal.ONE,
							"amount", amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(invoiceItemOutMap)) {
			return invoiceItemOutMap;
		}
		
		// 3、修改发票状态为INVOICE_APPROVED时，会自动创建 PaymentApplication // TODO 这里没有自动创建PA，有问题
		Map<String, Object> setInvoiceStatusOutMap;
		try {
			setInvoiceStatusOutMap = dispatcher.runSync("setInvoiceStatus",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"invoiceId",invoiceId,
							"statusId", "INVOICE_APPROVED"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(setInvoiceStatusOutMap)) {
			return setInvoiceStatusOutMap;
		}
		
		
		try {
			finAccount.refresh();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}
		// 返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("actualBalance", finAccount.get("actualBalance"));
		result.put("customerPartyId", customerPartyId);
		result.put("finAccountId", finAccountId);
		return result;
	}
	
	
	/**
	 * 充值、支付的公共参数检查
	 * @param dctx
	 * @param context
	 * @return
	 */
	private static Map<String, Object> checkInputParam(DispatchContext dctx, Map<String, Object> context){
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String finAccountId = (String) context.get("finAccountId");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		Map<String, Object> retMap = ServiceUtil.returnSuccess();
		
		// 金额必须为正数
		if (UtilValidate.isNotEmpty(amount) && amount.compareTo(BigDecimal.ZERO) <= 0) {
			Debug.logInfo("金额不合法，必须为正数", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceAccountingError, "AccountingFinAccountMustBePositive", locale));
		}

		// organizationPartyId 必须存在
		if(UtilValidate.isNotEmpty(organizationPartyId)){
			GenericValue partyGroup;
			try {
				partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if(null == partyGroup ){
				Debug.logInfo("商户："+organizationPartyId + "不存在", module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, 
						"CloudCardOrganizationPartyNotFound", UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
			}
			retMap.put("partyGroup", partyGroup);
		}
		
		// finAccountId 必须存在
		if(UtilValidate.isNotEmpty(finAccountId)){
			GenericValue finAccount;
			try {
				finAccount = delegator.findByPrimaryKey("FinAccount", UtilMisc.toMap("finAccountId", finAccountId));
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
			}
			if (null == finAccount) {
				return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardAccountNotFound", UtilMisc.toMap("finAccountId", finAccountId), locale));
			}
			retMap.put("finAccount", finAccount);
		}
		
		return retMap;
	}
}
