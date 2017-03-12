package com.banfftech.cloudcard.util;

import java.math.BigDecimal;
import java.util.Map;

import org.ofbiz.base.util.UtilDateTime;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityUtil;

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
        EntityCondition cond = EntityCondition.makeCondition(UtilMisc.toMap("partyId", userPartyId));
        GenericValue partyClassification = EntityUtil.getFirst(
                delegator.findList("PartyClassification", EntityCondition.makeCondition(cond, dateCond), null, UtilMisc.toList("-fromDate"), null, true));

        if (null == partyClassification) {
            // 如果没有，则返回 最初级
            Map<String, Object> finAccountMap = FastMap.newInstance();
            finAccountMap.put("partyId", userPartyId);
            finAccountMap.put("partyClassificationGroupId", "LEVEL_1");
            finAccountMap.put("fromDate", UtilDateTime.nowTimestamp());
            partyClassification = delegator.makeValue("PartyClassification", finAccountMap);
            partyClassification.create();
        }

        return partyClassification.getRelatedOneCache("PartyClassificationGroup");
    }

}
