package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.party.party.PartyRelationshipHelper;
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
	
	/**
	 * 查询卡信息
	 */
	public static Map<String, Object> findFinAccountByPartyId(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
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
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String finAccountId = (String) context.get("finAccountId");
		String amount = (String) context.get("amount");//TODO, 授权金额还未处理
		
		Timestamp fromDate = (Timestamp) context.get("fromDate");
		Timestamp thruDate = (Timestamp) context.get("thruDate");
		String cardCode = (String) context.get("cardCode");
		String organizationPartyId = (String) context.get("organizationPartyId");
		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		// 授权时判断用户是否存在
		Map<String, Object>	 checkCustomerOut  = getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(checkCustomerOut)) {
			return checkCustomerOut;
		}		
		String customerPartyId = (String) checkCustomerOut.get("customerPartyId");
//		String customerUserLoginId = (String) checkCustomerOut.get("customerUserLoginId");
		
		//创建paymentMethod和giftCard
		createPaymentMethodAndGiftCard(dispatcher, delegator, userLogin, cardCode, customerPartyId, finAccountId, partyGroup, fromDate, thruDate);

		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}

	/**
	 * 给用户开卡服务
	 * 	1、根据telNumber查找用户，不存在则创建
	 *	2、创建FinAccount，关联卡上二维码等信息。
	 *	3、充值
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
		String telNumber = (String) context.get("telNumber");
		String cardCode = (String) context.get("cardCode");
		BigDecimal amount = (BigDecimal) context.get("amount");
		
		Map<String, Object> result = ServiceUtil.returnSuccess();
		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if(partyGroup == null){
			Debug.logError("商户："+organizationPartyId + "不存在", module);
			return ServiceUtil.returnError("商户不存在");
		}
		
		// 1、根据telNumber查找用户，不存在则创建 
		Map<String, Object>	 checkCustomerOut  = getOrCreateCustomer(dctx, context);
		if (ServiceUtil.isError(checkCustomerOut)) {
			return checkCustomerOut;
		}		
		String customerPartyId = (String) checkCustomerOut.get("customerPartyId");
		String customerUserLoginId = (String) checkCustomerOut.get("customerUserLoginId");
		
		
		// 若客户与本商家没有客户关系,则建立关系
		Map<String,Object> partyRelationshipValues = UtilMisc.toMap(
				"userLogin", userLogin,
				"partyIdFrom", customerPartyId,
				"partyIdTo", organizationPartyId,
				"roleTypeIdFrom", "CUSTOMER",
				"roleTypeIdTo", "INTERNAL_ORGANIZATIO",
				"partyRelationshipTypeId", "CUSTOMER_REL"
				);
		List<GenericValue> relations = PartyRelationshipHelper.getActivePartyRelationships(delegator, partyRelationshipValues);
		if (UtilValidate.isEmpty(relations)) {
			Map<String, Object> relationOutMap;
			try {
				dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("partyId", customerPartyId, "roleTypeId", "CUSTOMER"));
				relationOutMap = dispatcher.runSync("createPartyRelationship", partyRelationshipValues);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(e.getMessage());
			}
			if (ServiceUtil.isError(relationOutMap)) {
				return relationOutMap;
			}
		}
		
		
		// 2、创建finAccount 和 paymentMethod
		Map<String, Object> finAccountMap = FastMap.newInstance();
		finAccountMap.put("userLogin", userLogin);
		finAccountMap.put("finAccountTypeId", "GIFTCERT_ACCOUNT"); //TODO 类型待定
		finAccountMap.put("finAccountName", partyGroup.get("groupName")+" 的卡");  //TODO
		finAccountMap.put("finAccountCode", cardCode);
		finAccountMap.put("currencyUomId", DEFAULT_CURRENCY_UOM_ID);
		finAccountMap.put("organizationPartyId", organizationPartyId);
		finAccountMap.put("ownerPartyId", customerPartyId);
		
		Map<String, Object> finAccountOutMap;
		try {
			finAccountOutMap = dispatcher.runSync("createFinAccount", finAccountMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (ServiceUtil.isError(finAccountOutMap)) {
			return finAccountOutMap;
		}
		String finAccountId = (String) finAccountOutMap.get("finAccountId");

		// 给卡主 OWNER 角色
		Map<String, Object> finAccountRoleOutMap;
		try {
			finAccountRoleOutMap = dispatcher.runSync("createFinAccountRole", 
					UtilMisc.toMap("userLogin", userLogin, "finAccountId", finAccountId, "partyId", customerPartyId, "roleTypeId","OWNER"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(e1.getMessage());
		}
		if (ServiceUtil.isError(finAccountRoleOutMap)) {
			return finAccountRoleOutMap;
		}

		// 创建PaymentMethod  GiftCard
		createPaymentMethodAndGiftCard(dispatcher, delegator, userLogin, cardCode, customerPartyId, finAccountId, partyGroup);

		// 3、充值
		Map<String, Object> rechargeCloudCardOutMap;
		try {
			rechargeCloudCardOutMap = dispatcher.runSync("rechargeCloudCard", 
					UtilMisc.toMap("userLogin", userLogin, "organizationPartyId",organizationPartyId, "finAccountId", finAccountId, 
							"amount",amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(e1.getMessage());
		}
		if (ServiceUtil.isError(rechargeCloudCardOutMap)) {
			return rechargeCloudCardOutMap;
		}
		
		
		// 4、返回结果
		result.put("customerPartyId", customerPartyId);
		result.put("customerUserLoginId", customerUserLoginId);
		result.put("amount", amount);
		result.put("actualBalance", rechargeCloudCardOutMap.get("actualBalance"));
		result.put("finAccountId", finAccountId);
		
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
		if (UtilValidate.isEmpty(amount) || amount.compareTo(BigDecimal.ZERO) <= 0) {
			Debug.logError("充值金额不合法", module);
			return ServiceUtil.returnError("充值金额不合法");
		}

		GenericValue partyGroup;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (UtilValidate.isEmpty(partyGroup)) {
			return ServiceUtil.returnError("商户不存在");
		}

		GenericValue finAccount;
		try {
			finAccount = delegator.findByPrimaryKey("FinAccount", UtilMisc.toMap("finAccountId", finAccountId));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (UtilValidate.isEmpty(finAccount)) {
			return ServiceUtil.returnError("用户账户不存在");
		}

		// 1、扣商户开卡余额
		// 获取商户金融账户
		EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
		EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("organizationPartyId", organizationPartyId,
				"ownerPartyId", organizationPartyId, "finAccountTypeId", "BANK_ACCOUNT", "statusId", "FNACT_ACTIVE"));
		GenericValue partyGroupFinAccount;
		try {
			partyGroupFinAccount = EntityUtil
					.getFirst(delegator.findList("FinAccount", EntityCondition.makeCondition(cond, dateCond), null,
							UtilMisc.toList("createdStamp DESC"), null, true));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (UtilValidate.isEmpty(partyGroupFinAccount)) {
			return ServiceUtil.returnError("商户没有金融账户");
		}
		String partyGroupFinAccountId = (String) partyGroupFinAccount.get("finAccountId");

		// 检查开卡余额是否够用
		BigDecimal actualBalance = partyGroupFinAccount.getBigDecimal("actualBalance");
		if (UtilValidate.isEmpty(actualBalance) || actualBalance.compareTo(amount) < 0) {
			return ServiceUtil.returnError("商户卖卡额度不足, 余额：" + actualBalance.toPlainString());
		}

		// TODO 可能会调整
		// 找到一个可用的 paymentMethod，
		// createPaymentAndFinAccountTrans服务需要传入一个关联了finAccountId的paymentMethodId才能创建finAccountTrans，才能对Finaccount进行扣款
		// 如果用 finAccountDeposit / finAccountWithdraw 这两个服务，则需要
		// productStoreId，需要给店家创建productStore
		EntityCondition pgPmCond = EntityCondition.makeCondition(UtilMisc.toMap("partyId", organizationPartyId,
				"paymentMethodTypeId", "FIN_ACCOUNT", "finAccountId", partyGroupFinAccountId));
		GenericValue partyGroupPaymentMethod;
		try {
			partyGroupPaymentMethod = EntityUtil
					.getFirst(delegator.findList("PaymentMethod", EntityCondition.makeCondition(pgPmCond, dateCond),
							null, UtilMisc.toList("createdStamp DESC"), null, true));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (UtilValidate.isEmpty(partyGroupPaymentMethod)) {
			return ServiceUtil.returnError("商户没有可供扣减额度的账户");
		}

		// 商户账户扣款（扣减开卡余额）
		Map<String, Object> finAccountWithdrawalOutMap;
		try {
			finAccountWithdrawalOutMap = dispatcher.runSync("createPaymentAndFinAccountTrans",
					UtilMisc.toMap("userLogin", userLogin, "statusId", "PMNT_SENT", "currencyUomId",
							DEFAULT_CURRENCY_UOM_ID, "finAccountTransTypeId", "WITHDRAWAL", 
							"paymentTypeId","COMMISSION_PAYMENT",// paymentType待定 
							"paymentMethodId", partyGroupPaymentMethod.get("paymentMethodId"),
							"paymentMethodTypeId", "FIN_ACCOUNT", "partyIdFrom", organizationPartyId, "partyIdTo",
							finAccount.get("ownerPartyId"), "amount", amount, "comments", "充值扣减卖卡额度",
							"isDepositWithDrawPayment", "Y"));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(e1.getMessage());
		}
		if (ServiceUtil.isError(finAccountWithdrawalOutMap)) {
			return finAccountWithdrawalOutMap;
		}

		// 用户账户存入
		// TODO

		Map<String, Object> result = ServiceUtil.returnSuccess();
		
		result.put("actualBalance", amount);
		result.put("customerPartyId", "");
		result.put("finAccountId", finAccountId);
		return result;
	}
	
	
	
	/**
	 * 根据电话号码检查是否有关联的注册用户，有则返回customerPartyId 和 customerUserLoginId
	 * 没有则 创建一个用户与电话号码关联，并返回新创建的 customerPartyId 和 customerUserLoginId
	 * @param dctx
	 * @param context
	 * @return
	 */
	private static Map<String, Object> getOrCreateCustomer(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		String telNumber = (String) context.get("telNumber");
		Locale locale = (Locale) context.get("locale");
		
		//TODO 密码随机生成？
		String currentPassword = (String) context.get("currentPassword");
		if(UtilValidate.isEmpty(currentPassword)){
			currentPassword =  "123456";
		}
		String currentPasswordVerify = (String) context.get("currentPasswordVerify");
		if(UtilValidate.isEmpty(currentPasswordVerify)){
			currentPasswordVerify =  "123456";
		}
		String customerPartyId;
		String customerUserLoginId;
		
		GenericValue customer;
		try {
			customer = EntityUtil.getFirst(delegator.findList("TelecomNumberAndUserLogin", 
					EntityCondition.makeCondition(
							EntityCondition.makeCondition(UtilMisc.toMap("contactNumber", telNumber)), 
							EntityUtil.getFilterByDateExpr()), null, UtilMisc.toList("partyId DESC"), null, false));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if(customer != null){
			customerPartyId = (String) customer.get("partyId");
			customerUserLoginId = (String) customer.get("userLoginId");
		}else{
			// 由于createPersonAndUserLogin 会自动新启一个事务，后面若失败，不能回滚，
			// 所以分步调用 createPerson 和 createUserLogin
			Map<String, Object> createPersonMap = UtilMisc.toMap("userLogin", userLogin, "firstName", telNumber, "lastName", "86");
			createPersonMap.put("preferredCurrencyUomId", DEFAULT_CURRENCY_UOM_ID);
			Map<String, Object> personOutMap;
			try {
				personOutMap = dispatcher.runSync("createPerson", createPersonMap);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(e.getMessage());
			}
			if (ServiceUtil.isError(personOutMap)) {
				return personOutMap;
			}
			customerPartyId = (String) personOutMap.get("partyId");
			//TODO,UserLoginId通常是可以直接由用户输入的用户名，这里由系统生成，自定义个前缀CC  代表 Cloud Card，减少冲突
			customerUserLoginId ="CC"+delegator.getNextSeqId("UserLogin");
			GenericValue systemUser;
			try {
				systemUser = delegator.findByPrimaryKey("UserLogin", UtilMisc.toMap("userLoginId", "system"));
			} catch (GenericEntityException e1) {
				Debug.logError(e1, module);
				return ServiceUtil.returnError(e1.getMessage());
			}
			
			Map<String, Object> createUserLoginMap = UtilMisc.toMap("userLogin", systemUser);
			createUserLoginMap.put("userLoginId", customerUserLoginId);
			createUserLoginMap.put("partyId", customerPartyId); 
			createUserLoginMap.put("currentPassword", currentPassword); 
			createUserLoginMap.put("currentPasswordVerify", currentPasswordVerify); //TODO
			createUserLoginMap.put("requirePasswordChange", "Y");
			
			Map<String, Object> userLoginOutMap;
			try {
				userLoginOutMap = dispatcher.runSync("createUserLogin", createUserLoginMap);
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(e.getMessage());
			}
			if (ServiceUtil.isError(userLoginOutMap)) {
				return userLoginOutMap;
			}

			Map<String, Object> partyTelecomOutMap;
			try {
				partyTelecomOutMap = dispatcher.runSync("createPartyTelecomNumber", 
						UtilMisc.toMap("userLogin", userLogin, "contactMechPurposeTypeId", "AS_USER_LOGIN_ID", "partyId", customerPartyId,
								"contactNumber", telNumber));
			} catch (GenericServiceException e) {
				Debug.logError(e, module);
				return ServiceUtil.returnError(e.getMessage());
			}
			if (ServiceUtil.isError(partyTelecomOutMap)) {
				return partyTelecomOutMap;
			}
		}
		
		Map<String, Object> retMap =  ServiceUtil.returnSuccess();
		retMap.put("customerPartyId", customerPartyId);
		retMap.put("customerUserLoginId", customerUserLoginId);
		return retMap;
	}
	
	/**
	 * @param dispatcher
	 * @param delegator
	 * @param userLogin
	 * @param cardCode
	 * @param customerPartyId
	 * @param finAccountId
	 * @param partyGroup
	 * @return
	 */
	private static Map<String,Object> createPaymentMethodAndGiftCard(LocalDispatcher dispatcher, Delegator delegator,
			GenericValue userLogin, String cardCode, String customerPartyId, String finAccountId,
			GenericValue partyGroup) {
		
		return createPaymentMethodAndGiftCard(dispatcher,delegator,userLogin,cardCode,customerPartyId,finAccountId,partyGroup,null,null);
	
	}
	
	private static Map<String, Object> createPaymentMethodAndGiftCard(LocalDispatcher dispatcher, Delegator delegator,
			GenericValue userLogin, String cardCode, String customerPartyId, String finAccountId,
			GenericValue partyGroup, Timestamp fromDate, Timestamp thruDate) {

		String paymentMethodId;
		Map<String, Object> giftCardMap = FastMap.newInstance();
		giftCardMap.put("userLogin", userLogin);
		giftCardMap.put("partyId", customerPartyId); 
		giftCardMap.put("description", partyGroup.get("groupName")+" 的卡");  //TODO
		giftCardMap.put("cardNumber", cardCode);
		Map<String, Object> giftCardOutMap;
		try {
			giftCardOutMap = dispatcher.runSync("createGiftCard", giftCardMap);
		} catch (GenericServiceException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		if (ServiceUtil.isError(giftCardOutMap)) {
			return giftCardOutMap;
		}
		paymentMethodId = (String) giftCardOutMap.get("paymentMethodId");
		GenericValue paymentMethod;
		try {
			paymentMethod = delegator.findByPrimaryKey("PaymentMethod", UtilMisc.toMap("paymentMethodId", paymentMethodId));
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		// 关联 paymentMethod 与 finAccount
		paymentMethod.set("finAccountId", finAccountId);
		paymentMethod.set("fromDate", fromDate);
		paymentMethod.set("thruDate", thruDate);
		try {
			paymentMethod.store();
		} catch (GenericEntityException e) {
			Debug.logError(e, module);
			return ServiceUtil.returnError(e.getMessage());
		}
		
		return giftCardOutMap;
	}
}
