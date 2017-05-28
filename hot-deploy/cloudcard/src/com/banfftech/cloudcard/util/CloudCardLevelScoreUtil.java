package com.banfftech.cloudcard.util;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.CloudCardHelper;
import com.banfftech.cloudcard.constant.CloudCardConstant;

import javolution.util.FastMap;

/**
 * 用户积分和等级相关帮助类
 * 
 * @author cy
 *
 */
public class CloudCardLevelScoreUtil {

    public static final String module = CloudCardLevelScoreUtil.class.getName();

    /**
     * 获取用户的积分账户，若不存在，则创建一个
     * 
     * @param dctx
     * @param userPartyId
     * @return
     * @throws GenericEntityException
     */
    public static GenericValue getOrCreateUserScoreAccount(Delegator delegator, String userPartyId) throws GenericEntityException {

        GenericValue userScoreAccount = CloudCardHelper.getUserScoreAccount(delegator, userPartyId, false);
        if (null == userScoreAccount) {
            String finAccountId = delegator.getNextSeqId("FinAccount");
            Map<String, Object> finAccountMap = FastMap.newInstance();
            finAccountMap.put("finAccountId", finAccountId);
            finAccountMap.put("finAccountTypeId", CloudCardConstant.FANT_SCORE);
            finAccountMap.put("statusId", "FNACT_ACTIVE");
            finAccountMap.put("finAccountName", "用户" + userPartyId + "的积分账户");
            finAccountMap.put("organizationPartyId", CloudCardConstant.PLATFORM_PARTY_ID);
            finAccountMap.put("ownerPartyId", userPartyId);
            finAccountMap.put("currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID);
            finAccountMap.put("postToGlAccountId", "213200");
            finAccountMap.put("isRefundable", "N");

            // 保存finaccount数据
            userScoreAccount = delegator.makeValue("FinAccount", finAccountMap);
            userScoreAccount.create();
        }

        return userScoreAccount;
    }

    /**
     * 获取用户的积分值
     * 
     * @param delegator
     * @param userPartyId
     * @return
     * @throws GenericEntityException
     */
    public static BigDecimal getUserScoreAmount(Delegator delegator, String userPartyId) throws GenericEntityException {
        GenericValue scoreAccount = getOrCreateUserScoreAccount(delegator, userPartyId);
        BigDecimal score = scoreAccount.getBigDecimal("actualBalance");
        if (null == score) {
            return CloudCardHelper.ZERO;
        }
        return score.setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);
    }

    /**
     * 获取会员等级（会员分类）实体
     * 
     * @param delegator
     * @param userPartyId
     * @return
     * @throws GenericEntityException
     */
    public static GenericValue getUserLevel(Delegator delegator, String userPartyId) throws GenericEntityException {

        EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
        EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("partyId", userPartyId,"partyClassificationTypeId","LEVEL_CLASSIFICATION"));
        GenericValue partyClassification = EntityUtil.getFirst(
                delegator.findList("PartyClassificationAndPartyClassificationGroup", EntityCondition.makeCondition(cond, dateCond), null, UtilMisc.toList("-fromDate"), null, true));

        if (null == partyClassification) {
            // 如果没有，则返回 最初级
            Map<String, Object> partyClassificationtMap = FastMap.newInstance();
            partyClassificationtMap.put("partyId", userPartyId);
            partyClassificationtMap.put("partyClassificationGroupId", "LEVEL_1");
            partyClassificationtMap.put("fromDate", UtilDateTime.nowTimestamp());
            partyClassification = delegator.makeValue("PartyClassification", partyClassificationtMap);
            partyClassification.create();
            partyClassification = EntityUtil.getFirst(
                    delegator.findList("PartyClassificationAndPartyClassificationGroup", EntityCondition.makeCondition(cond, dateCond), null, UtilMisc.toList("-fromDate"), null, true));
        }

        return partyClassification;
    }

    /**
     * 内部服务--增加用户积分的服务， 云卡支付完成时 eca会触发此服务
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> addUserScore(DispatchContext dctx, Map<String, Object> context) {

        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String customerPartyId = (String) context.get("partyId"); // 用户partyId
        BigDecimal amount = (BigDecimal) context.get("amount"); // 增加多少积分
        String paymentId = (String) context.get("paymentId"); // 如果有的话，也记录下支付id

        Map<String, Object> retMap = ServiceUtil.returnSuccess();

        Map<String, Object> finAccountDepoistOutMap = null;
        try {
            GenericValue scoreAccount = CloudCardLevelScoreUtil.getOrCreateUserScoreAccount(delegator, customerPartyId);
            GenericValue systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
            finAccountDepoistOutMap = dispatcher.runSync("createFinAccountTrans",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "finAccountId", scoreAccount.getString("finAccountId"), "partyId",
                            customerPartyId, "amount", amount, "finAccountTransTypeId", "DEPOSIT", "paymentId", paymentId, "reasonEnumId", "FATR_PURCHASE",
                            "glAccountId", "210000", "comments", "积分增加", "statusId", "FINACT_TRNS_APPROVED"));
        } catch (GenericServiceException | GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(finAccountDepoistOutMap)) {
            return finAccountDepoistOutMap;
        }

        return retMap;
    }
    
    /**
     * 获取店铺信用等级
     * 
     * @param delegator
     * @param userPartyId
     * @return
     * @throws GenericEntityException
     */
    public static GenericValue getBizLevel(Delegator delegator, String storeId) throws GenericEntityException {
        EntityCondition dateCond = EntityUtil.getFilterByDateExpr();
        EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("partyId", storeId,"partyClassificationTypeId","STORE_LEVEL_CLASSIFI"));
        GenericValue partyClassification = EntityUtil.getFirst(
                delegator.findList("PartyClassificationAndPartyClassificationGroup", EntityCondition.makeCondition(cond, dateCond), null, UtilMisc.toList("-fromDate"), null, true));

        if (null == partyClassification) {
            // 如果没有，则返回 最初级
            Map<String, Object> partyClassificationtMap = FastMap.newInstance();
            partyClassificationtMap.put("partyId", storeId);
            partyClassificationtMap.put("partyClassificationGroupId", "LEVEL_5");
            partyClassificationtMap.put("fromDate", UtilDateTime.nowTimestamp());
            partyClassification = delegator.makeValue("PartyClassification", partyClassificationtMap);
            partyClassification.create();
        }

        return partyClassification;
    }

}
