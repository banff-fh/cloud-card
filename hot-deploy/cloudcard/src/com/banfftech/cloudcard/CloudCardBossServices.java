package com.banfftech.cloudcard;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.base.util.UtilProperties;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.condition.EntityOperator;
import org.ofbiz.entity.model.ModelEntity;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.banfftech.cloudcard.constant.CloudCardConstant;

import javolution.util.FastList;
import javolution.util.FastMap;

/**
 * 库胖卡商家相关的服务
 * 
 * @author ChenYu
 *
 */
public class CloudCardBossServices {

    public static final String module = CloudCardBossServices.class.getName();

    /**
     * 商户开通申请 通过搜集的信息创建一个SurveyResponse 与 SurveyResponseAnswer
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizCreateApply(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // 店名
        String storeName = (String) context.get("storeName");
        if (null != storeName) {
            storeName = storeName.trim();
        }
        // 商铺详细地址
        String storeAddress = (String) context.get("storeAddress");
        if (null != storeAddress) {
            storeAddress = storeAddress.trim();
        }
        // 店主电话
        String teleNumber = (String) context.get("teleNumber");
        if (null != teleNumber) {
            teleNumber = teleNumber.trim();
        }

        String personName = (String) context.get("personName"); // 店主姓名
        String description = (String) context.get("description"); // 店铺描述
        String longitude = (String) context.get("longitude"); // 经度
        String latitude = (String) context.get("latitude"); // 纬度

        String surveyId = "SURV_CC_STORE_INFO"; // 用于 收集库胖卡商家的 “调查（Survey）实体”
        String statusId = "SRS_CREATED";

        // 必要的参数检验逻辑
        // 比如：
        // 1、传入telnumber已经有关联店铺了。不能再申请开店
        // 2、传入的电话号码 或 店铺名 已经有了 有效的申请，也不能再次申请开店
        // （有效的状态为 创建 或 已经通过 的申请， 而 拒绝 和 取消 状态的申请是可以重新申请的）
        try {
            // TODO 目前一个B端用户只关联一家店铺，故需要检查 申请用的号码 是否已经是一个店长了。
            GenericValue userByTeleNumber = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
            if (null != userByTeleNumber) {
                List<String> organizationPartyIds = CloudCardHelper.getOrganizationPartyId(delegator, (String) userByTeleNumber.get("partyId"));
                if (UtilValidate.isNotEmpty(organizationPartyIds)) {
                    Debug.logWarning("teleNumber[" + teleNumber + "] has been associated with a store owner", module);
                    return ServiceUtil
                            .returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTelHasBeenAssociatedWithAstoreOwner", locale));
                }
            }

            List<GenericValue> oldAnswers = delegator.findList("SurveyResponseAndAnswer",
                    EntityCondition.makeCondition(EntityOperator.OR,
                            EntityCondition.makeCondition(UtilMisc.toMap("surveyQuestionId", "SQ_CC_S_OWNER_TEL", "textResponse", teleNumber)),
                            EntityCondition.makeCondition(UtilMisc.toMap("surveyQuestionId", "SQ_CC_STORE_NAME", "textResponse", storeName))),
                    null, UtilMisc.toList("-answeredDate"), null, false);

            // 检查是否有状态为 创建 和 已通过 的数据
            oldAnswers = EntityUtil.filterByOr(oldAnswers,
                    UtilMisc.toList(EntityCondition.makeCondition("statusId", "SRS_CREATED"), EntityCondition.makeCondition("statusId", "SRS_ACCEPTED")));

            if (UtilValidate.isNotEmpty(oldAnswers)) {
                Debug.logError("teleNumber[" + teleNumber + "] or storeName[" + storeName + "] has been used to apply for a shop", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardRepeatedApplyForCreateStore", locale));
            }
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 创建“调查回答”，用于记录开店申请者提交的信息
        Map<String, Object> createSurveyResponseOutMap;
        try {
            createSurveyResponseOutMap = dispatcher.runSync("createSurveyResponse",
                    UtilMisc.toMap("locale", locale, "surveyId", surveyId, "statusId", statusId, "answers",
                            UtilMisc.toMap("SQ_CC_STORE_NAME", storeName, "SQ_CC_STORE_ADDR", storeAddress, "SQ_CC_S_OWNER_TEL", teleNumber,
                                    "SQ_CC_S_OWNER_NAME", personName, "SQ_CC_STORE_DESC", description, "SQ_CC_S_LONGITUDE", longitude, "SQ_CC_S_LATITUDE",
                                    latitude)));
        } catch (GenericServiceException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (!ServiceUtil.isSuccess(createSurveyResponseOutMap)) {
            return createSurveyResponseOutMap;
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("responseId", createSurveyResponseOutMap.get("surveyResponseId"));
        return result;
    }

    /**
     * 商户创建圈子 创建个partyGroup，并用partyRelationship 关联 商家与这个代表圈子的partyGroup
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizCreateGroup(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        String groupName = (String) context.get("groupName"); // 圈子名

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }
        GenericValue ccStore = (GenericValue) checkInputParamRet.get("ccStore");

        // 如果没有传入“圈子名：groupName”这个参数， 则使用 "商家名" + "的圈子" 作为名称
        if (UtilValidate.isEmpty(groupName)) {
            groupName = ccStore.getString("groupName") + "的圈子";
        }

        // 检查是否已经 建立/加入 圈子
        List<EntityCondition> condList = FastList.newInstance();
        condList.add(EntityCondition.makeCondition("partyIdTo", organizationPartyId));
        condList.add(EntityCondition.makeCondition("roleTypeIdFrom", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID));
        condList.add(EntityCondition.makeCondition("partyRelationshipTypeId", CloudCardConstant.STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID));
        condList.add(EntityUtil.getFilterByDateExpr());
        List<GenericValue> partyRelationships = null;
        try {
            // 这里可以查询缓存， 缓存未命中自动查数据库
            partyRelationships = delegator.findList("PartyRelationship", EntityCondition.makeCondition(condList), null, null, null, true);
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), "Problem finding PartyRelationships. ", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        if (UtilValidate.isNotEmpty(partyRelationships)) {
            // 已经在圈子里，不能创建新的圈子
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsInGroupCanNotCreateAnother", locale));
        }

        // 创建 “圈子”以及 圈子与圈主关系
        Map<String, Object> createPartyGroupOutMap;
        String groupId;
        try {

            // system用户
            GenericValue systemUser = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
            // 创建 “圈子”
            createPartyGroupOutMap = dispatcher.runSync("createPartyGroup", UtilMisc.toMap("locale", locale, "userLogin", systemUser, "groupName", groupName));
            if (ServiceUtil.isError(createPartyGroupOutMap) || ServiceUtil.isFailure(createPartyGroupOutMap)) {
                return createPartyGroupOutMap;
            }

            groupId = (String) createPartyGroupOutMap.get("partyId");

            // 创建 圈子角色
            Map<String, Object> createPartyRoleOut = dispatcher.runSync("createPartyRole",
                    UtilMisc.toMap("locale", locale, "userLogin", systemUser, "partyId", groupId, "roleTypeId", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID));
            if (!ServiceUtil.isSuccess(createPartyRoleOut)) {
                return createPartyRoleOut;
            }

            // 确保 店家 具有 OWNER 角色
            Map<String, Object> ensurePartyRoleOut = dispatcher.runSync("ensurePartyRole", UtilMisc.toMap("locale", locale, "userLogin", systemUser, "partyId",
                    organizationPartyId, "roleTypeId", CloudCardConstant.STORE_GROUP_OWNER_ROLE_TYPE_ID));
            if (!ServiceUtil.isSuccess(ensurePartyRoleOut)) {
                return ensurePartyRoleOut;
            }

            // 让 店家 成为 圈子 的 "圈主"
            Map<String, Object> relationOutMap = dispatcher.runSync("createPartyRelationship",
                    UtilMisc.toMap("locale", locale, "userLogin", systemUser, "partyIdFrom", groupId, "partyIdTo", organizationPartyId, "roleTypeIdFrom",
                            CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID, "roleTypeIdTo", CloudCardConstant.STORE_GROUP_OWNER_ROLE_TYPE_ID,
                            "partyRelationshipTypeId", CloudCardConstant.STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID));
            if (!ServiceUtil.isSuccess(relationOutMap)) {
                return relationOutMap;
            }

        } catch (GenericServiceException | GenericEntityException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("groupId", groupId);
        result.put("groupName", groupName);
        return result;
    }

    /**
     * 向某B端用戶发起邀请，邀请其加入某个圈子
     * 
     * <pre>
     * 以下情况 <strong>均无法</strong> 邀请：
     *   1、自己不在圈子里，
     *   2、自己不是圈主，
     *   3、被邀请的用户未注册，
     *   4、被邀请的用户不是某个店铺的管理员
     *   5、被邀请的用户是本店的另一个管理员（一个店可能有多个管理员）
     *   6、被邀请的店铺已经在某个圈子里了
     * </pre>
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizSentGroupInvitation(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        String teleNumber = (String) context.get("teleNumber"); // 需要邀请的店主电话

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (ServiceUtil.isError(checkInputParamRet) || ServiceUtil.isFailure(checkInputParamRet)) {
            return checkInputParamRet;
        }

        GenericValue otherStoreGroup = null;
        GenericValue ourStoreGroup = null;
        String groupId = null;
        String storeId = null; // 被邀请店的店id

        // 必要的规则检查：
        try {
            ourStoreGroup = CloudCardHelper.getStoreGroupByStoreId(delegator, organizationPartyId, true);
            if (null == ourStoreGroup) {
                // 自己不存在圈子
                Debug.logWarning("This store[" + organizationPartyId + "] is not in a group of store, can not invite others", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsNotInAgroup", locale));
            }
            boolean isOwner = CloudCardHelper.isStoreGroupOwner(delegator, organizationPartyId, true);
            if (!isOwner) {
                // 自己不是圈主
                Debug.logWarning("This store[" + organizationPartyId + "] is not a store group owner, can not invite others", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsNotAstoreGroupOwner", locale));
            }

            groupId = ourStoreGroup.getString("partyId");

            GenericValue userByTeleNumber = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
            if (null == userByTeleNumber) {
                // 被邀请用户未注册
                Debug.logWarning("This phone number [" + teleNumber + "] is not registered as a user, can not be invited", module);
                return ServiceUtil
                        .returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTelIsNotAssociatedWithAstoreOwner", locale));
            }
            List<String> storeIdList = CloudCardHelper.getOrganizationPartyId(delegator, userByTeleNumber.getString("partyId"));
            if (UtilValidate.isEmpty(storeIdList)) {
                // 存在这个用户，但不是店铺管理员
                Debug.logWarning("This user [" + teleNumber + "] is not the manager, can not be invited", module);
                return ServiceUtil
                        .returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTelIsNotAssociatedWithAstoreOwner", locale));
            }
            if (storeIdList.contains(organizationPartyId)) {
                // 被邀请用户和自己是同一个店的两个管理员
                Debug.logWarning("This user [" + teleNumber + "]  and you in the same store[" + organizationPartyId + "], can not be invited", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserIsInTheSameStore", locale));
            }

            storeId = storeIdList.get(0);
            otherStoreGroup = CloudCardHelper.getStoreGroupByStoreId(delegator, storeId, true);
            if (null != otherStoreGroup) {
                // 已经加入某个圈子了
                Debug.logWarning("This user [" + teleNumber + "] is not the manager, can not be invited", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsInGroupCanNotJoinAnother", locale));
            }

        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 发起邀请之前应该先检查是否已经发出了邀请，并且此邀请不是拒绝状态
        List<EntityCondition> condList = FastList.newInstance();
        condList.add(EntityCondition.makeCondition("partyIdFrom", organizationPartyId));
        condList.add(EntityCondition.makeCondition("partyId", storeId));
        GenericValue oldPartyInvitation = null;
        try {
            oldPartyInvitation = EntityUtil.getFirst(delegator.findList("PartyInvitation",
                    EntityCondition.makeCondition(UtilMisc.toMap("partyIdFrom", organizationPartyId, "partyId", storeId)), null, null, null, false));
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), "Problem finding PartyInvitation. ", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (null != oldPartyInvitation) {
            String oldPartyInvitationStatus = oldPartyInvitation.getString("statusId");
            if (!"PARTYINV_DECLINED".equals(oldPartyInvitationStatus)) {
                Debug.logWarning("There has been an effective invitation[" + oldPartyInvitation.getString("partyInvitationId") + "]", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInvitationAlreadyExists", locale));
            }
        }

        // 发起邀请
        String partyInvitationId = null;
        Map<String, Object> createPartyInvitationOutMap;
        try {
            createPartyInvitationOutMap = dispatcher.runSync("createPartyInvitation", UtilMisc.toMap("locale", locale, "userLogin", userLogin, "partyId",
                    storeId, "partyIdFrom", organizationPartyId, "statusId", "PARTYINV_SENT"));
            if (!ServiceUtil.isSuccess(createPartyInvitationOutMap)) {
                return createPartyInvitationOutMap;
            }
            partyInvitationId = (String) createPartyInvitationOutMap.get("partyInvitationId");

            // 创建 createPartyInvitationGroupAssoc 和
            // createPartyInvitationRoleAssoc 这样对方在通过 acceptPartyInvitation服务
            // 接收邀请的时候就会自动创建 partyRelationship 和 partyRole了
            // TODO 是否需要？
            Map<String, Object> createPartyInvitationGroupAssocOutMap = dispatcher.runSync("createPartyInvitationGroupAssoc",
                    UtilMisc.toMap("locale", locale, "userLogin", userLogin, "partyInvitationId", partyInvitationId, "partyIdTo", groupId));
            if (!ServiceUtil.isSuccess(createPartyInvitationGroupAssocOutMap)) {
                return createPartyInvitationGroupAssocOutMap;
            }

            Map<String, Object> createPartyInvitationRoleAssocOutMap = dispatcher.runSync("createPartyInvitationRoleAssoc", UtilMisc.toMap("locale", locale,
                    "userLogin", userLogin, "partyInvitationId", partyInvitationId, "roleTypeId", CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID));
            if (!ServiceUtil.isSuccess(createPartyInvitationRoleAssocOutMap)) {
                return createPartyInvitationRoleAssocOutMap;
            }

        } catch (GenericServiceException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("groupId", groupId);
        result.put("partyInvitationId", partyInvitationId);
        return result;
    }

    /**
     * 我的圈子
     * 
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizMyGroup(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        Integer viewIndex = (Integer) context.get("viewIndex");
        Integer viewSize = (Integer) context.get("viewSize");

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (ServiceUtil.isError(checkInputParamRet) || ServiceUtil.isFailure(checkInputParamRet)) {
            return checkInputParamRet;
        }

        // 查找圈子
        GenericValue partyRelationship = null;
        GenericValue storeGroup = null;
        String groupId = null;
        boolean isGroupOwner = false;
        try {
            partyRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, true);
            if (null != partyRelationship) {
                // 在圈子里，
                groupId = partyRelationship.getString("partyIdFrom");
                storeGroup = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", groupId));
                isGroupOwner = CloudCardHelper.isStoreGroupOwnerRelationship(partyRelationship);
            }
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 没有圈子的情况，
        if (null == partyRelationship || null == storeGroup) {
            result.put("isJoinGroup", CloudCardConstant.IS_N);
            result.put("isGroupOwner", CloudCardConstant.IS_N);

            // 查询邀请列表
            List<GenericValue> existPartyInvitations = null;
            try {
                existPartyInvitations = delegator.findList("PartyInvitation",
                        EntityCondition.makeCondition(UtilMisc.toMap("partyId", organizationPartyId, "statusId", "PARTYINV_SENT")), null,
                        UtilMisc.toList("-" + ModelEntity.CREATE_STAMP_FIELD), null, false);
                if (UtilValidate.isNotEmpty(existPartyInvitations)) {
                    List<Map<String, String>> invitations = FastList.newInstance();
                    for (GenericValue gv : existPartyInvitations) {

                        String partyInvitationId = gv.getString("partyInvitationId");
                        String partyIdFrom = gv.getString("partyIdFrom");

                        if (UtilValidate.isEmpty(partyInvitationId) || UtilValidate.isEmpty(partyIdFrom)) {
                            continue;
                        }
                        // 验证是否为 邀请加入圈子，
                        GenericValue partyInvitationRoleAssoc = delegator.findByPrimaryKeyCache("PartyInvitationRoleAssoc",
                                UtilMisc.toMap("partyInvitationId", partyInvitationId, "roleTypeId", CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID));
                        if (null == partyInvitationRoleAssoc) {
                            continue;
                        }

                        GenericValue partyFrom = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", partyIdFrom));
                        if (null == partyFrom) {
                            continue;
                        }
                        Map<String, String> itemMap = UtilMisc.toMap("partyInvitationId", partyInvitationId);
                        itemMap.put("partyIdFrom", partyIdFrom);
                        itemMap.put("fromName", partyFrom.getString("groupName"));
                        invitations.add(itemMap);
                    }
                    result.put("invitations", invitations);// 把已有的邀请列表返回
                }
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), "Problem finding PartyInvitation. ", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
            return result;
        }

        // 有圈子的情况
        // 获取圈友列表
        Map<String, Object> lookupMap = FastMap.newInstance();
        Map<String, Object> inputFieldMap = FastMap.newInstance();
        inputFieldMap.put("partyIdFrom", groupId);
        inputFieldMap.put("roleTypeIdFrom", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID);
        inputFieldMap.put("partyRelationshipTypeId", CloudCardConstant.STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID);
        lookupMap.put("inputFields", inputFieldMap);
        lookupMap.put("entityName", "PartyRelationship");
        lookupMap.put("orderBy", "fromDate");// 圈主一定是最先创建的记录
        lookupMap.put("viewIndex", viewIndex);
        lookupMap.put("viewSize", viewSize);
        lookupMap.put("filterByDate", "Y");

        Map<String, Object> performFindListOut = null;
        try {
            performFindListOut = dispatcher.runSync("performFindList", lookupMap);
            if (!ServiceUtil.isSuccess(performFindListOut)) {
                return performFindListOut;
            }

            List<GenericValue> groupMemberRelationsList = UtilGenerics.checkList(performFindListOut.get("list"));
            if (UtilValidate.isNotEmpty(groupMemberRelationsList)) {
                List<Map<String, String>> partners = FastList.newInstance();
                for (GenericValue gv : groupMemberRelationsList) {
                    String storeId = gv.getString("partyIdTo");
                    GenericValue store = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", storeId));
                    boolean tmpIsOwner = CloudCardHelper.isStoreGroupOwnerRelationship(gv);
                    Map<String, String> tmpMap = UtilMisc.toMap("storeId", storeId, "storeName", store.getString("groupName"));
                    tmpMap.put("storeImg", store.getString("logoImageUrl"));
                    tmpMap.put("isGroupOwner", tmpIsOwner ? CloudCardConstant.IS_Y : CloudCardConstant.IS_N);
                    if (tmpIsOwner) {
                        partners.add(0, tmpMap);
                    } else {
                        partners.add(tmpMap);
                    }
                }
                result.put("partners", partners);
            }
        } catch (GenericServiceException | GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // TODO 圈主 需要查询一些 圈子相关的金额信息
        if (isGroupOwner) {
            result.put("crossStoreAmount", CloudCardHelper.ZERO); // 跨店消费额，圈主卡 到
                                                                  // 圈友店 消费总额，
            result.put("presellAmount", CloudCardHelper.ZERO); // 已卖卡总额，圈主卖出的卡总金额，
            result.put("totalConsumptionAmount", CloudCardHelper.ZERO); // 已消费总额，圈主本店消费
                                                                        // +
                                                                        // 到圈友店里跨店消费，
            result.put("balance", CloudCardHelper.ZERO); // 剩余额度，剩余卖卡额度
            result.put("income", CloudCardHelper.ZERO); // 收益总额，因为跨店消费的圈主给圈友的打折而产生的收益总额，
        }

        result.put("isJoinGroup", CloudCardConstant.IS_Y);
        result.put("isGroupOwner", isGroupOwner ? CloudCardConstant.IS_Y : CloudCardConstant.IS_N);
        result.put("groupId", groupId);
        result.put("groupName", storeGroup.getString("groupName"));
        return result;
    }

    /**
     * 参数检查
     * 
     * @param dctx
     * @param context
     * @return
     * @throws GenericEntityException
     */
    private static Map<String, Object> checkInputParam(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        Map<String, Object> result = ServiceUtil.returnSuccess();

        String organizationPartyId = (String) context.get("organizationPartyId");
        if (UtilValidate.isNotEmpty(organizationPartyId)) {
            // organizationPartyId 合法性
            GenericValue ccStore = null;
            try {
                ccStore = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), "Problem finding PartyGroup. ", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));

            }
            if (null == ccStore) {
                Debug.logWarning("商户：" + organizationPartyId + "不存在", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardOrganizationPartyNotFound",
                        UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
            }
            result.put("ccStore", ccStore);

            // 数据权限检查: 登录用户是否是本店的管理员
            if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)) {
                Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + organizationPartyId + "的管理人员，不能查看圈子信息", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
            }
        }

        // 返回结果
        return result;
    }
}
