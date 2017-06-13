package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilDateTime;
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
import org.ofbiz.entity.util.EntityListIterator;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceUtil;

import com.auth0.jwt.JWTExpiredException;
import com.auth0.jwt.JWTVerifier;
import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.sms.SmsServices;
import com.banfftech.cloudcard.util.CloudCardLevelScoreUtil;

import javolution.util.FastList;
import javolution.util.FastMap;
import javolution.util.FastSet;

/**
 * 库胖卡商家相关的服务
 *
 * @author ChenYu
 *
 */
public class CloudCardBossServices {

    public static final String module = CloudCardBossServices.class.getName();
	public static final String resourceError = "cloudcardErrorUiLabels";

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
                List<String> organizationPartyIds = CloudCardHelper.getOrganizationPartyId(delegator, userByTeleNumber.getString("partyId"));
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
        GenericValue ccStore = (GenericValue) checkInputParamRet.get("store");
        // system用户
        GenericValue systemUser = (GenericValue) checkInputParamRet.get("systemUserLogin");

        // 如果没有传入“圈子名：groupName”这个参数， 则使用 "商家名" + "的圈子" 作为名称
        if (UtilValidate.isEmpty(groupName)) {
            groupName = ccStore.getString("groupName") + "的圈子";
        }

        // 创建 “圈子”以及 圈子与圈主关系
        Map<String, Object> createPartyGroupOutMap;
        String groupId;
        try {

            // 检查是否已经 建立/加入 圈子
            GenericValue myGroupRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, true);
            if (UtilValidate.isNotEmpty(myGroupRelationship)) {
                // 已经在圈子里，不能创建新的圈子
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsInGroupCanNotCreateAnother", locale));
            }

            // 创建 “圈子”
            createPartyGroupOutMap = dispatcher.runSync("createPartyGroup", UtilMisc.toMap("locale", locale, "userLogin", systemUser, "groupName", groupName));
            if (!ServiceUtil.isSuccess(createPartyGroupOutMap)) {
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
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
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
                Debug.logWarning("This store[" + storeId + "] is already in a Group, Can Not join another one.", module);
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
        List<GenericValue> oldPartyInvitations = null;
        try {
            oldPartyInvitations = delegator.findList("PartyInvitation",
                    EntityCondition.makeCondition(UtilMisc.toMap("partyIdFrom", organizationPartyId, "partyId", storeId)), null, null, null, false);
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), "Problem finding PartyInvitation. ", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (UtilValidate.isNotEmpty(oldPartyInvitations)) {
            for (GenericValue gv : oldPartyInvitations) {
                String oldPartyInvitationStatus = gv.getString("statusId");
                if ("PARTYINV_SENT".equals(oldPartyInvitationStatus) || "".equals(oldPartyInvitationStatus)) {
                    Debug.logWarning("There has been an effective invitation[" + gv.getString("partyInvitationId") + "]", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInvitationAlreadyExists", locale));
                }
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
     * B端 接收或拒绝 加入圈子的邀请
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizAcceptGroupInvitation(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        String partyInvitationId = (String) context.get("partyInvitationId"); // 邀请id
        String isAcceptYN = (String) context.get("isAccept");
        boolean isAccept = !CloudCardConstant.IS_N.equalsIgnoreCase(isAcceptYN);

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        GenericValue oldStoreGroup = null;
        GenericValue partyInvitation = null;

        try {
            // 检查partyInvitationId是否存在
            partyInvitation = delegator.findByPrimaryKey("PartyInvitation", UtilMisc.toMap("partyInvitationId", partyInvitationId));
            if (null == partyInvitation) {
                Debug.logWarning("This PartyInvitation[" + organizationPartyId + "] is NOT EXIST!", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotFindInvitation", locale));
            }

            // 检查partyInvitationId是否针对本店的
            if (!organizationPartyId.equals(partyInvitation.getString("partyId"))) {
                Debug.logWarning("Illegal data access PartyInvitation[" + organizationPartyId + "] is NOT related to Store[" + organizationPartyId + "]",
                        module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardWasNotInvited", locale));
            }

            // 检查partyInvitation的状态是否为PARTYINV_SENT
            String statusId = partyInvitation.getString("statusId");
            if (!"PARTYINV_SENT".equals(statusId)) {
                Debug.logWarning("partyInvitation statusId is error, expected PARTYINV_SENT， But it is " + statusId, module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInvitationStatusUnexpected", locale));
            }

            // 检查自己店是否已经加入了别的圈子
            oldStoreGroup = CloudCardHelper.getStoreGroupByStoreId(delegator, organizationPartyId, true);
            if (null != oldStoreGroup) {
                // 已经加入某个圈子了,不能再接受本次邀请了
                // 此情况下：是否应当要将 partyInvitation 状态改为 PARTYINV_CANCELLED？
                partyInvitation.set("statusId", "PARTYINV_CANCELLED");
                partyInvitation.store();

                Debug.logWarning("This store [" + organizationPartyId + "] is already in a Group, Can Not join another one.", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardIsInGroupCanNotJoinAnother", locale));
            }

            // system用户
            GenericValue systemUser = (GenericValue) checkInputParamRet.get("systemUserLogin");
            if (!isAccept) {
                // 拒绝邀请
                partyInvitation.set("statusId", "PARTYINV_DECLINED");
                partyInvitation.store();

            } else { // 接收邀请

                // 普通圈友的角色， * 正常情况list应该只有一条数据
                List<GenericValue> partyInvitationRoleAssocList = delegator.findByAnd("PartyInvitationRoleAssoc",
                        UtilMisc.toMap("partyInvitationId", partyInvitationId));
                if (UtilValidate.isNotEmpty(partyInvitationRoleAssocList)) {
                    Map<String, Object> tmpCtx = UtilMisc.toMap("locale", locale, "userLogin", systemUser, "partyId", organizationPartyId);
                    for (GenericValue gv : partyInvitationRoleAssocList) {
                        tmpCtx.put("roleTypeId", gv.getString("roleTypeId"));
                        Map<String, Object> ensurePartyRoleOut = dispatcher.runSync("ensurePartyRole", tmpCtx);
                        if (!ServiceUtil.isSuccess(ensurePartyRoleOut)) {
                            return ensurePartyRoleOut;
                        }
                    }
                }

                // 获取需要关联到的圈子id * 正常情况list应该只有一条数据
                List<GenericValue> partyInvitationGroupAssocList = delegator.findByAnd("PartyInvitationGroupAssoc",
                        UtilMisc.toMap("partyInvitationId", partyInvitationId));
                if (UtilValidate.isNotEmpty(partyInvitationGroupAssocList)) {
                    Map<String, Object> tmpCtx = UtilMisc.toMap("locale", locale, "userLogin", systemUser, "partyIdTo", organizationPartyId, "roleTypeIdTo",
                            CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID, "partyRelationshipTypeId",
                            CloudCardConstant.STORE_GROUP_PARTY_RELATION_SHIP_TYPE_ID, "roleTypeIdFrom", CloudCardConstant.STORE_GROUP_ROLE_TYPE_ID, "statusId",
                            CloudCardConstant.SG_REL_STATUS_ACTIVE);
                    for (GenericValue gv : partyInvitationGroupAssocList) {
                        tmpCtx.put("partyIdFrom", gv.getString("partyIdTo"));
                        Map<String, Object> createPartyRelationshipOut = dispatcher.runSync("createPartyRelationship", tmpCtx);
                        if (!ServiceUtil.isSuccess(createPartyRelationshipOut)) {
                            return createPartyRelationshipOut;
                        }
                    }
                }

                // 修改状态
                partyInvitation.set("statusId", "PARTYINV_ACCEPTED");
                partyInvitation.store();
            }

        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();

        result.put("partyInvitationId", partyInvitationId);
        result.put("isAccept", isAccept ? CloudCardConstant.IS_Y : CloudCardConstant.IS_N);
        return result;
    }

    /**
     * B端 退出圈子/ 圈主踢出圈友 接口
     *
     * <pre>
     *      场景1：圈友主动发起退出圈子请求
     *      场景2：圈主发起踢出圈友的请求
     * </pre>
     *
     * <pre>
     *      如果是圈主踢出圈友的场景，此圈友必须是冻结状态 且 没有未结算的金额。
     *          即 圈主踢出圈友时， 必须先对该圈友进行冻结（接口 圈主冻结/解冻 圈友）， 然后结清未结算金额，否则调用此接口会直接提示错误。
     *
     *      如果圈友主动发起退出操作的场景， 若有未结算金额时，也会给出错误提示。
     * </pre>
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizExitGroup(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        String storeId = (String) context.get("storeId");
        if (UtilValidate.isWhitespace(storeId)) {
            // 未传入storeId，默认为 organizationPartyId
            storeId = organizationPartyId;
        }

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        String commentsExit = "主动退出圈子";
        String commentsKickOut = "被圈主踢出圈子";

        try {
            String myGroupId = null;
            GenericValue myPartyRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, false);
            if (UtilValidate.isNotEmpty(myPartyRelationship)) {
                myGroupId = myPartyRelationship.getString("partyIdFrom");
            }
            if (UtilValidate.isEmpty(myGroupId)) {
                // 用户自己 没有有效的圈子关系了，忽略本次调用，直接返回成功，
                Debug.logWarning("This store[" + organizationPartyId + "] is not in a group now! this operation will be ignored", module);
                return ServiceUtil.returnSuccess();
            }

            // 自己是否为圈主
            boolean isGroupOwner = CloudCardHelper.isStoreGroupOwnerRelationship(myPartyRelationship);

            // 传入id不一样时，表示圈主要踢出圈友
            if (!UtilValidate.areEqual(storeId, organizationPartyId)) {

                if (!isGroupOwner) {
                    // 如果不是圈主，则不能踢人
                    Debug.logWarning("The store[" + organizationPartyId + "] is not the owner of the group[" + myGroupId + "], can't kick out this store["
                            + storeId + "]", module);
                    return ServiceUtil
                            .returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotGroupOwnerCanNotKickOthers", locale));
                }

                GenericValue groupStoreRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, storeId, false);
                if (UtilValidate.isEmpty(groupStoreRelationship)) {
                    // 要踢出的店已经不在圈子里面了，忽略本次调用，直接返回成功
                    Debug.logWarning("This store[" + storeId + "] is not in a group now! this operation will be ignored", module);
                    return ServiceUtil.returnSuccess();
                }

                if (!myGroupId.equals(groupStoreRelationship.getString("partyIdFrom"))) {
                    // 要踢出的店铺不属于同一个圈子，忽略操作，直接返回成功
                    Debug.logWarning("This store[" + storeId + "] is not in my group[" + myGroupId + "! this operation will be ignored", module);
                    return ServiceUtil.returnSuccess();
                }

                // 对面也是圈主，不能踢出 * 业务上应该不存在这样的情况
                if (CloudCardHelper.isStoreGroupOwnerRelationship(groupStoreRelationship)) {
                    Debug.logWarning("This store[" + storeId + "] is also the owner of group[" + myGroupId + "], can't kick it out", module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotKickOutGroupOwner", locale));
                }

                // 只能踢出 冻结状态 的圈友
                String relStatusId = groupStoreRelationship.getString("statusId");
                if (!CloudCardConstant.SG_REL_STATUS_FROZEN.equalsIgnoreCase(relStatusId)) {
                    Debug.logWarning("Must first freeze this store[" + storeId + "] of the group[" + myGroupId + "], to kick out " + relStatusId, module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardMustFirstFreezeToKickOut", locale));
                }

                // 再判断是否结清跨店交易产生的 未结算金额 才能踢出
                BigDecimal settlementAmount = CloudCardHelper.getSettlementAmountByStoreId(delegator, storeId);
                if (settlementAmount.compareTo(CloudCardHelper.ZERO) != 0) {
                    Debug.logWarning("店铺[" + storeId + "] 未结算金额:" + settlementAmount.toPlainString(), module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTheStoreNotSettled", locale));
                }

                // 将圈友关系设置为过期
                groupStoreRelationship.set("thruDate", UtilDateTime.nowTimestamp());
                groupStoreRelationship.set("comments", commentsKickOut);
                groupStoreRelationship.store();
            } else {
                if (isGroupOwner) {
                    // 如果自己是圈主，需要查看还有多少圈友，还有圈友时，自己不能退出圈子吧
                    List<String> partnerIdList = CloudCardHelper.getStoreGroupPartnerIdListByGroupId(delegator, myGroupId, true);
                    if (null != partnerIdList && partnerIdList.size() > 1) {
                        Debug.logWarning("There are other partners in the Group[" + myGroupId + "], the owner[" + organizationPartyId + "] can not quit group",
                                module);
                        return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPartnerExistsCanNotExit", locale));
                    }
                }
                // 判断是否结清跨店交易产生的 未结算金额
                BigDecimal settlementAmount = CloudCardHelper.getSettlementAmountByStoreId(delegator, organizationPartyId);
                if (settlementAmount.compareTo(CloudCardHelper.ZERO) != 0) {
                    Debug.logWarning("店铺[" + organizationPartyId + "] 未结算金额:" + settlementAmount.toPlainString(), module);
                    return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTheStoreNotSettled", locale));
                }

                myPartyRelationship.set("thruDate", UtilDateTime.nowTimestamp());
                myPartyRelationship.set("comments", commentsExit);
                myPartyRelationship.store();
            }
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        // 返回成功
        Map<String, Object> result = ServiceUtil.returnSuccess();
        return result;
    }

    /**
     * B端 圈主冻结/解冻圈友 接口
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizFreezeGroupPartner(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        String storeId = (String) context.get("storeId");

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        boolean isGroupOwner = false;
        boolean isFrozen = true;
        try {
            GenericValue myGroupRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, true);
            if (null != myGroupRelationship) {
                isGroupOwner = CloudCardHelper.isStoreGroupOwnerRelationship(myGroupRelationship);
            }

            if (!isGroupOwner) {
                // 不是圈主，没权冻结别人，直接返回失败
                Debug.logWarning("This store[" + organizationPartyId + "] is not the owner of group, can not freeze/unfreeze the partner", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotGroupOwnerCanNotFreezeOthers", locale));
            }

            // 获取要冻结的店的 圈子关系
            GenericValue storeGroupRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, storeId, false);
            if (null == storeGroupRelationship) {
                // 店铺不在圈子里，不能冻结、解冻
                Debug.logWarning("Store[" + storeId + "] is not in the group, can't freeze/unfreeze it", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardStoreNotInAGroup", locale));
            }

            String myGroupId = CloudCardHelper.getGroupIdByRelationship(myGroupRelationship);
            String storeGroupId = CloudCardHelper.getGroupIdByRelationship(storeGroupRelationship);
            if (!UtilValidate.areEqual(myGroupId, storeGroupId)) {
                // 要冻结的店铺与自己店铺不在同一个圈子中
                Debug.logWarning("Store[" + storeId + "] is not in the same group with my store[" + organizationPartyId + "], can't freeze/unfreeze it",
                        module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotInTheSameGroup", locale));
            }

            if (CloudCardHelper.isStoreGroupOwnerRelationship(storeGroupRelationship)) {
                // 被冻结或解冻的店铺也是一个圈主，返回失败
                Debug.logWarning("This store[" + storeId + "] is also the owner of group, can't freeze/unfreeze it", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardCanNotFreezeGroupOwner", locale));
            }

            String statusId = storeGroupRelationship.getString("statusId");
            if (CloudCardHelper.isFrozenGroupRelationship(storeGroupRelationship)) {
                statusId = CloudCardConstant.SG_REL_STATUS_ACTIVE;
                isFrozen = false;
            } else {
                statusId = CloudCardConstant.SG_REL_STATUS_FROZEN;
                isFrozen = true;
            }
            storeGroupRelationship.put("statusId", statusId);
            storeGroupRelationship.store();

        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回成功
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("storeId", storeId);
        result.put("isFrozen", isFrozen ? CloudCardConstant.IS_Y : CloudCardConstant.IS_N);
        return result;
    }

    /**
     * B端 圈主解散圈子 接口
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizDissolveGroup(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        String comments = "圈主解散了圈子";

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }
        GenericValue systemUserLogin = (GenericValue) checkInputParamRet.get("systemUserLogin");

        boolean isGroupOwner = false;
        try {
            GenericValue myGroupRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, organizationPartyId, false);
            if (null == myGroupRelationship) {
                // 如果不存在圈子，直接返回成功
                Debug.logWarning("This store[" + organizationPartyId + "] is not in a group now! just return success.", module);
                return ServiceUtil.returnSuccess();
            }
            isGroupOwner = CloudCardHelper.isStoreGroupOwnerRelationship(myGroupRelationship);

            if (!isGroupOwner) {
                // 不是圈主，没权解散圈子，返回失败
                Debug.logWarning("This store[" + organizationPartyId + "] is not the owner of group, can not dissolve the group", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotGroupOwnerCanNotDissolveGroup", locale));
            }

            String myGroupId = CloudCardHelper.getGroupIdByRelationship(myGroupRelationship);

            List<GenericValue> storeRelList = CloudCardHelper.getStoreGroupRelationshipByGroupId(delegator, myGroupId, false);
            List<GenericValue> onlyPartnerRelList = CloudCardHelper.getStoreGroupPartnerRelationships(storeRelList);

            if (UtilValidate.isNotEmpty(onlyPartnerRelList)) {
                // 查找圈友中是否存在未冻结
                for (GenericValue gv : onlyPartnerRelList) {
                    if (!CloudCardHelper.isFrozenGroupRelationship(gv)) {
                        // 有没有冻结的，不能解散
                        Debug.logWarning("Store[" + gv.getString("partyIdTo") + "] is Not frozen store in a group,can not dissolve the group! ", module);
                        return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardMustFreezeAllToDissolve", locale));
                    }
                }
            }

            // 判断是否结清跨店交易产生的 未结算金额 有则不能解散
            BigDecimal settlementAmount = CloudCardHelper.getSettlementAmountByStoreId(delegator, organizationPartyId);
            if (settlementAmount.compareTo(CloudCardHelper.ZERO) != 0) {
                Debug.logWarning("店铺[" + organizationPartyId + "] 未结算金额:" + settlementAmount.toPlainString(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTheStoreNotSettled", locale));
            }

            // 正式进行解散操作(让关系过期)
            for (GenericValue gv : storeRelList) {
                gv.set("thruDate", UtilDateTime.nowTimestamp());
                gv.set("comments", comments);
            }
            delegator.storeAll(storeRelList);

            // 把圈子本身对应的那个 party 设置成 PARTY_DISABLED
            Map<String, Object> setPartyStatusOut = dispatcher.runSync("setPartyStatus",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "statusId", "PARTY_DISABLED", "partyId", myGroupId));

            if (!ServiceUtil.isSuccess(setPartyStatusOut)) {
                return setPartyStatusOut;
            }

        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回成功
        Map<String, Object> result = ServiceUtil.returnSuccess();
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
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId"); // 店家partyId
        Integer viewIndex = (Integer) context.get("viewIndex");
        Integer viewSize = (Integer) context.get("viewSize");

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
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
                    tmpMap.put("isFrozen", CloudCardHelper.isFrozenGroupRelationship(gv) ? CloudCardConstant.IS_Y : CloudCardConstant.IS_N);
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
            // 跨店消费额，圈主卡 到 圈友店 消费总额，
            BigDecimal crossStoreAmount = CloudCardHelper.ZERO;
            // 已卖卡总额，圈主卖出的卡总金额，
            BigDecimal presellAmount = CloudCardHelper.ZERO;
            // 已消费总额，圈主本店消费 + 到圈友店里跨店消费，
            BigDecimal totalConsumptionAmount = CloudCardHelper.ZERO;
            // 剩余额度，剩余卖卡额度
            BigDecimal balance = CloudCardHelper.ZERO;
            // 收益总额，因为跨店消费的圈主给圈友的打折而产生的收益总额，
            BigDecimal income = CloudCardHelper.ZERO;


            result.put("crossStoreAmount", crossStoreAmount);
            result.put("presellAmount", presellAmount);
            result.put("totalConsumptionAmount", totalConsumptionAmount);
            result.put("balance", balance);
            result.put("income", income);
        }

        result.put("isJoinGroup", CloudCardConstant.IS_Y);
        result.put("isGroupOwner", CloudCardHelper.bool2YN(isGroupOwner));
        result.put("groupId", groupId);
        result.put("groupName", storeGroup.getString("groupName"));
        return result;
    }

    /**
     * B端 查看店铺简要信息 接口
     *
     * @param dctx
     * @param context
     * @return
     */
    public static Map<String, Object> bizGetStoreInfo(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String storeId = (String) context.get("storeId"); // 要查询的店家partyId

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        GenericValue store = (GenericValue) checkInputParamRet.get("store");

        // TODO storeImg 暂时使用老的 “从系统配置中获取”的方式，
        // 以后直接从partyGroup实体中获取 logoImageUrl 字段
        String storeImg = EntityUtilProperties.getPropertyValue("cloudcard", "cardImg." + storeId, delegator);
        String storeAddress = "";
        String storeTeleNumber = "";

        List<GenericValue> PartyAndContactMechs = FastList.newInstance();
        try {
            PartyAndContactMechs = delegator.findList("PartyAndContactMech", EntityCondition.makeCondition("partyId", storeId), null, null, null, true);
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        if (UtilValidate.isNotEmpty(PartyAndContactMechs)) {
            for (GenericValue partyAndContactMech : PartyAndContactMechs) {
                String cmType = partyAndContactMech.getString("contactMechTypeId");
                if ("POSTAL_ADDRESS".equals(cmType)) {
                    storeAddress = partyAndContactMech.getString("paAddress1");
                } else if (("TELECOM_NUMBER".equals(cmType))) {
                    storeTeleNumber = partyAndContactMech.getString("tnContactNumber");
                }
            }
        }

        // 圈子相关
        boolean isJoinGroup = false;
        boolean isGroupOwner = false;
        boolean isFrozen = false;
        try {
            GenericValue partyRelationship = CloudCardHelper.getGroupRelationShipByStoreId(delegator, storeId, false);
            if (null != partyRelationship) {
                isJoinGroup = true;
                isGroupOwner = CloudCardHelper.isStoreGroupOwnerRelationship(partyRelationship);
                isFrozen = CloudCardHelper.isFrozenGroupRelationship(partyRelationship);
            }
        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        ;
        // 待结算金额settlementAmount的计算
        BigDecimal settlementAmount = CloudCardHelper.getSettlementAmountByStoreId(delegator, storeId);

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("storeId", storeId);
        result.put("storeName", store.getString("groupName"));
        result.put("storeImg", storeImg);
        result.put("storeAddress", storeAddress);
        result.put("storeTeleNumber", storeTeleNumber);
        result.put("settlementAmount", settlementAmount);
        result.put("isJoinGroup", CloudCardHelper.bool2YN(isJoinGroup));
        result.put("isGroupOwner", CloudCardHelper.bool2YN(isGroupOwner));
        result.put("isFrozen", CloudCardHelper.bool2YN(isFrozen));
        return result;
    }

    /**
     * 发起结算
     *
     * @param dctx
     * @param context
     * @return
     * @deprecated
     */
    public static Map<String, Object> bizDoSettlement(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");

        String storeId = (String) context.get("storeId"); // 对方店家的partyId
        String organizationPartyId = (String) context.get("organizationPartyId"); // 本店家partyId

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        BigDecimal settlementAmount = (BigDecimal) context.get("settlementAmount");
        settlementAmount = settlementAmount.setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);
        BigDecimal actualSettlementAmount = (BigDecimal) context.get("actualSettlementAmount");
        actualSettlementAmount = actualSettlementAmount.setScale(CloudCardHelper.decimals, CloudCardHelper.rounding);

        if (settlementAmount.compareTo(CloudCardHelper.ZERO) <= 0 || actualSettlementAmount.compareTo(CloudCardHelper.ZERO) <= 0) {
            Debug.logWarning("金额不合法，必须为正数", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceAccountingError, "AccountingFinAccountMustBePositive", locale));
        }

        // 双方的 结算账户
        GenericValue myAccount = CloudCardHelper.getSettlementAccount(delegator, organizationPartyId);
        GenericValue storeAccount = CloudCardHelper.getSettlementAccount(delegator, storeId);

        BigDecimal storeAmount = CloudCardHelper.getSettlementAmountByAccount(storeAccount);
        if (storeAmount.compareTo(CloudCardHelper.ZERO) == 0) {
            Debug.logWarning("店铺[" + storeId + "] 已经结清，不能再次对此店发起结算", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTheStoreHasBeenSettled", locale));
        }

        Map<String, Object> finAccountWithdrawalOutMap;
        try {
            // system账户
            GenericValue systemUserLogin = (GenericValue) checkInputParamRet.get("systemUserLogin");

            // 检查是否已有发出但对方还未处理的结算
            List<GenericValue> oldPayments = delegator
                    .findByAnd("Payment",
                            UtilMisc.toMap("partyIdFrom", organizationPartyId, "partyIdTo", storeId, "statusId", "PMNT_SENT", "paymentTypeId", "DISBURSEMENT",
                                    "paymentMethodTypeId", CloudCardConstant.PMT_CASH, "roleTypeIdTo", CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID),
                            null);
            if (UtilValidate.isNotEmpty(oldPayments)) {
                Debug.logWarning("已经发起过结算，需要等待对手方处理", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardTheStoreRequestHasBeenIssued", locale));
            }

            // 扣除本店结算账户相应的金额
            finAccountWithdrawalOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "statusId", "PMNT_SENT", "currencyUomId",
                            CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "actualCurrencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
                            "finAccountTransTypeId", "WITHDRAWAL", "paymentTypeId", "DISBURSEMENT", "finAccountId", myAccount.getString("finAccountId"),
                            "paymentMethodTypeId", CloudCardConstant.PMT_CASH, "partyIdFrom", organizationPartyId, "partyIdTo", storeId, "roleTypeIdTo",
                            CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID, "amount", settlementAmount, "actualCurrencyAmount", actualSettlementAmount,
                            "comments", "结算：圈主付款", "reasonEnumId", "FATR_PURCHASE"));
        } catch (GenericServiceException | GenericEntityException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(finAccountWithdrawalOutMap)) {
            return finAccountWithdrawalOutMap;
        }

        String withdrawalPaymentId = (String) finAccountWithdrawalOutMap.get("paymentId");

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("settlementId", withdrawalPaymentId);
        return result;
    }

    /**
     * 结算确认/拒绝
     *
     * @param dctx
     * @param context
     * @return
     * @deprecated
     */
    public static Map<String, Object> bizSettlementConfirm(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        String settlementId = (String) context.get("settlementId");
        String isConfirm = (String) context.get("isConfirm");
        if (!CloudCardConstant.IS_Y.equals(isConfirm)) {
            isConfirm = CloudCardConstant.IS_N;
        }

        String organizationPartyId = (String) context.get("organizationPartyId"); // 本店家partyId

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        BigDecimal settlementAmount = CloudCardHelper.ZERO;
        BigDecimal actualSettlementAmount = CloudCardHelper.ZERO;

        try {

            GenericValue systemUserLogin = (GenericValue) checkInputParamRet.get("systemUserLogin");

            // 检查结算的状态
            GenericValue settlePayment = delegator.findByPrimaryKey("Payment", UtilMisc.toMap("paymentId", settlementId));
            if (UtilValidate.isEmpty(settlePayment)) {
                Debug.logWarning("无效的结算", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardParamIllegal", locale));
            }

            String statusId = settlePayment.getString("statusId");
            if (!"PMNT_SENT".equals(statusId)) {
                // 状态不符，可能已经 确认/拒绝 过了
                Debug.logWarning("不能确认或拒绝结算[" + settlementId + "]，当前状态[" + statusId + "]", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardSettlementStatusUnexpected", locale));
            }

            settlementAmount = settlePayment.getBigDecimal("amount");
            actualSettlementAmount = settlePayment.getBigDecimal("actualCurrencyAmount");

            // 1、拒绝结算：
            if (CloudCardConstant.IS_N.equals(isConfirm)) {
                Map<String, Object> setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus",
                        UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "paymentId", settlementId, "statusId", "PMNT_CANCELLED"));
                if (ServiceUtil.isError(setPaymentStatusOutMap)) {
                    return setPaymentStatusOutMap;
                }
                GenericValue finAccountTrans = settlePayment.getRelatedOne("FinAccountTrans");
                if (UtilValidate.isNotEmpty(finAccountTrans)) {
                    finAccountTrans.set("statusId", "FINACT_TRNS_CANCELED");
                    finAccountTrans.store();
                }
                // 返回结果
                Map<String, Object> result = ServiceUtil.returnSuccess();
                result.put("settlementId", settlementId);
                result.put("isConfirm", isConfirm);
                result.put("settlementAmount", settlementAmount);
                result.put("actualSettlementAmount", actualSettlementAmount);
                return result;
            }

            // 2、同意结算 商家结算账户：
            String partyIdFrom = settlePayment.getString("partyIdFrom");
            GenericValue myAccount = CloudCardHelper.getSettlementAccount(delegator, organizationPartyId, true);

            Map<String, Object> finAccountReceiptOutMap = dispatcher.runSync("createPaymentAndFinAccountTransForCloudCard",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "statusId", "PMNT_CONFIRMED", "currencyUomId",
                            CloudCardConstant.DEFAULT_CURRENCY_UOM_ID, "actualCurrencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
                            "finAccountTransTypeId", "DEPOSIT", "paymentTypeId", "RECEIPT", "finAccountId", myAccount.getString("finAccountId"),
                            "paymentMethodTypeId", CloudCardConstant.PMT_CASH, "partyIdFrom", partyIdFrom, "partyIdTo", organizationPartyId, "roleTypeIdTo",
                            CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID, "amount", settlementAmount, "actualCurrencyAmount", actualSettlementAmount,
                            "comments", "结算：圈友收款", "reasonEnumId", "FATR_PURCHASE"));
            if (ServiceUtil.isError(finAccountReceiptOutMap)) {
                return finAccountReceiptOutMap;
            }

            String receiptPaymentId = (String) finAccountReceiptOutMap.get("paymentId");

            // 3、应用掉 结算 的两个payment
            Map<String, Object> paymentApplicationOutMap = dispatcher.runSync("createPaymentApplication", UtilMisc.toMap("userLogin", systemUserLogin, "locale",
                    locale, "amountApplied", settlementAmount, "paymentId", settlementId, "toPaymentId", receiptPaymentId));
            if (ServiceUtil.isError(paymentApplicationOutMap)) {
                return paymentApplicationOutMap;
            }

            // 4、修改圈主发起结算的 payment的状态为PMNT_CONFIRMED
            Map<String, Object> setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "paymentId", settlementId, "statusId", "PMNT_CONFIRMED"));

            if (ServiceUtil.isError(setPaymentStatusOutMap)) {
                return setPaymentStatusOutMap;
            }

        } catch (GenericEntityException | GenericServiceException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("settlementId", settlementId);
        result.put("isConfirm", isConfirm);
        result.put("settlementAmount", settlementAmount);
        result.put("actualSettlementAmount", actualSettlementAmount);
        return result;
    }

    /**
     * 获取待确认结算信息
     *
     * @param dctx
     * @param context
     * @return
     * @deprecated
     */
    public static Map<String, Object> bizGetUnconfirmedSettlementInfo(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        String settlementId = null;
        String storeId = (String) context.get("storeId");
        String organizationPartyId = (String) context.get("organizationPartyId");
        if (UtilValidate.isEmpty(storeId)) {
            storeId = organizationPartyId;
        }

        BigDecimal settlementAmount = CloudCardHelper.ZERO;
        BigDecimal actualSettlementAmount = CloudCardHelper.ZERO;

        try {
            String groupOwnerId = CloudCardHelper.getGroupOwneIdByStoreId(delegator, storeId, true);
            // 检查是否已有发出但对方还未处理的结算
            GenericValue settlement = EntityUtil
                    .getFirst(delegator.findByAnd("Payment",
                            UtilMisc.toMap("partyIdFrom", groupOwnerId, "partyIdTo", storeId, "statusId", "PMNT_SENT", "paymentTypeId", "DISBURSEMENT",
                                    "paymentMethodTypeId", CloudCardConstant.PMT_CASH, "roleTypeIdTo", CloudCardConstant.STORE_GROUP_PARTNER_ROLE_TYPE_ID),
                            null));
            if (null != settlement) {
                settlementId = settlement.getString("paymentId");
                settlementAmount = settlement.getBigDecimal("amount");
                actualSettlementAmount = settlement.getBigDecimal("actualCurrencyAmount");

            }

        } catch (GenericEntityException e) {
            Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("settlementId", settlementId);
        result.put("settlementAmount", settlementAmount);
        result.put("actualSettlementAmount", actualSettlementAmount);
        return result;
    }

    /**
     * TODO
     *
     * <pre>
          <service name="bizSettlementRequest" engine="java"
            location="com.banfftech.cloudcard.CloudCardBossServices" invoke=
    "bizSettlementRequest" auth="true">
            <description>B端 催款，提醒圈主进行结算</description>
            <attribute name="organizationPartyId" type="String" mode=
    "IN" optional="false"><description>店家Id（自己的商家id）</description></attribute>
          </service>
     * </pre>
     *
     * @param dctx
     * @param context
     * @return
     * @deprecated
     */
    public static Map<String, Object> bizSettlementRequest(DispatchContext dctx, Map<String, Object> context) {
        //LocalDispatcher dispatcher = dctx.getDispatcher();
        //Delegator delegator = dispatcher.getDelegator();
        //Locale locale = (Locale) context.get("locale");
        // GenericValue userLogin = (GenericValue) context.get("userLogin");

        Map<String, Object> checkInputParamRet = checkInputParam(dctx, context);
        if (!ServiceUtil.isSuccess(checkInputParamRet)) {
            return checkInputParamRet;
        }

        // 返回结果
        Map<String, Object> result = ServiceUtil.returnSuccess();
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
            GenericValue organization = null;
            try {
                organization = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", organizationPartyId));
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), "Problem finding PartyGroup. ", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));

            }
            if (null == organization) {
                Debug.logWarning("商户：" + organizationPartyId + "不存在", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardOrganizationPartyNotFound",
                        UtilMisc.toMap("organizationPartyId", organizationPartyId), locale));
            }
            result.put("organization", organization);

            // 数据权限检查: 登录用户是否是本店的管理员
            if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)) {
                Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + organizationPartyId + "的管理人员，不能操作", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
            }
        }
        String storeId = (String) context.get("storeId");
        if (UtilValidate.isNotEmpty(storeId)) {
            // storeId 合法性
            GenericValue store = null;
            try {
                store = delegator.findByPrimaryKeyCache("PartyGroup", UtilMisc.toMap("partyId", storeId));
            } catch (GenericEntityException e) {
                Debug.logError(e.getMessage(), "Problem finding PartyGroup. ", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));

            }
            if (null == store) {
                Debug.logWarning("商户：" + storeId + "不存在", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardOrganizationPartyNotFound",
                        UtilMisc.toMap("organizationPartyId", storeId), locale));
            }
            result.put("store", store);
        }

        // 后续可能要用到 system用户操作
        GenericValue systemUserLogin = (GenericValue) context.get("systemUserLogin");
        if (null == systemUserLogin) {
            try {
                systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
                result.put("systemUserLogin", systemUserLogin);
            } catch (GenericEntityException e1) {
                Debug.logError(e1.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
            }
        }

        // 返回结果
        return result;
    }

    /**
     * 获取无卡开卡验证码
     *
     * @param dctx
     * @param context
     * @return
     */
	public static Map<String, Object> getPurchaseCardCaptchaOfUser(DispatchContext dctx,Map<String, Object> context) {
		String teleNumber = (String) context.get("teleNumber");
		BigDecimal amount = (BigDecimal) context.get("amount");
		String smsType = CloudCardConstant.USER_PURCHASE_CARD_CAPTCHA_SMS_TYPE;
		context.put("smsType", smsType);
		context.put("amount", amount);
		context.put("isValid", "N");

		Map<String, Object> result = SmsServices.getSMSCaptcha(dctx, context);
		result.put("teleNumber", teleNumber);
		result.put("amount", amount);
		result.put("status", "Y");
		return result;
	}

	/**
     * 获取无卡充值验证码
     *
     * @param dctx
     * @param context
     * @return
     */
	public static Map<String, Object> getRechargeCaptchaOfUser(DispatchContext dctx,Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String teleNumber = (String) context.get("teleNumber");
		String amount = (String) context.get("amount");
		String smsType = CloudCardConstant.USER_PURCHASE_CARD_CAPTCHA_SMS_TYPE;
		context.put("smsType", smsType);
		context.put("amount", amount);
		context.put("isValid", "N");

		GenericValue customer;
		try {
			customer = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(customer)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserNotExistError", locale));
		}

		Map<String, Object> result = SmsServices.getSMSCaptcha(dctx, context);
		result.put("teleNumber", teleNumber);
		result.put("amount", amount);
		result.put("status", "Y");
		return result;
	}

    /**
     * 获取用户消费验证码
     *
     * @param dctx
     * @param context
     * @return
     */
	public static Map<String, Object> getPayCaptchaOfUser(DispatchContext dctx,Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String teleNumber = (String) context.get("teleNumber");
		String amount = (String) context.get("amount");
		String smsType = CloudCardConstant.USER_PAY_CAPTCHA_SMS_TYPE;
		context.put("smsType", smsType);
		context.put("amount", amount);
		context.put("isValid", "N");

		GenericValue customer;
		try {
			customer = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(customer)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserNotExistError", locale));
		}

		Map<String, Object> result = SmsServices.getSMSCaptcha(dctx, context);
		result.put("teleNumber", teleNumber);
		result.put("amount", amount);
		result.put("status", "Y");
		return result;
	}

	/**
     * 根据电话号码获取用户卡包
     *
     * @param dctx
     * @param context
     * @return
     */
	public static Map<String, Object> getCloudcardsOfUser(DispatchContext dctx,Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String teleNumber = (String) context.get("teleNumber");
		String qrCode = (String) context.get("qrCode");
		BigDecimal amount = (BigDecimal) context.get("amount");

		context.put("amount", amount);
        String partyId = null;
		 // 如果扫的码 cardCode字段是 个用户付款码，则根据付款码进行自动找卡
        if (UtilValidate.isNotEmpty(qrCode) && qrCode.startsWith(CloudCardConstant.CODE_PREFIX_PAY_)) {
        	String iss = EntityUtilProperties.getPropertyValue("cloudcard", "qrCode.issuer", delegator);
            String tokenSecret = EntityUtilProperties.getPropertyValue("cloudcard", "qrCode.secret", delegator);
            try {
                JWTVerifier verifier = new JWTVerifier(tokenSecret, null, iss);
                String qrCodeTmp = qrCode.replace(CloudCardConstant.CODE_PREFIX_PAY_, "");
                Map<String, Object> claims = verifier.verify(qrCodeTmp);
                partyId = (String) claims.get("user");
            } catch (JWTExpiredException e1) {
                // 用户付款码已过期
                Debug.logWarning("付款码已经过期", module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPaymentCodeExpired", locale));
            } catch (Exception e) {
                // 非法的码
                Debug.logError(e.getMessage(), module);
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPaymentCodeIllegal", locale));
            }
		}else if(UtilValidate.isNotEmpty(teleNumber)){
			GenericValue customer = null;
			try {
				customer = CloudCardHelper.getUserByTeleNumber(delegator, teleNumber);
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}

			if(UtilValidate.isEmpty(customer)){
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserNotExistError", locale));
			}

			partyId = customer.getString("partyId");
		}

        if(UtilValidate.isEmpty(partyId)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserNotExistError", locale));
        }

        Map<String, Object> result = FastMap.newInstance();
		try {
		context.put("partyId",partyId );
		context.put("type", "biz");
		result = CloudCardQueryServices.myCloudCards(dctx, context);
		} catch (Exception e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		result.put("teleNumber", teleNumber);
		result.put("amount", amount);
		return result;
	}

	public static Map<String, Object> activateCloudCardAndRechargeByTelNumber(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String organizationPartyId = (String) context.get("organizationPartyId");
		String teleNumber = (String) context.get("teleNumber");
		BigDecimal amount = (BigDecimal) context.get("amount");
		String captcha = (String) context.get("captcha");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		//判断验证码是否正确
		EntityCondition captchaCondition = EntityCondition.makeCondition(
				EntityCondition.makeCondition("teleNumber", EntityOperator.EQUALS, teleNumber),
				EntityUtil.getFilterByDateExpr(),
				EntityCondition.makeCondition("isValid", EntityOperator.EQUALS,"N"),EntityCondition.makeCondition("smsType", EntityOperator.EQUALS,CloudCardConstant.USER_PURCHASE_CARD_CAPTCHA_SMS_TYPE));

		GenericValue sms = null;
		try {
			sms = EntityUtil.getFirst(
					delegator.findList("SmsValidateCode", captchaCondition, null,UtilMisc.toList("-" + ModelEntity.CREATE_STAMP_FIELD), null, false)
					);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGetCAPTCHAFailedError", locale));
		}

		if(UtilValidate.isEmpty(sms)){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGetCAPTCHAFailedError", locale));
		}

		if(!captcha.equalsIgnoreCase(sms.getString("captcha"))){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCaptchaCheckFailedError", locale));
		}

		Map<String, Object> activateCloudCardAndRechargeOut = FastMap.newInstance();
		try {
			activateCloudCardAndRechargeOut = dispatcher.runSync("activateCloudCardAndRecharge",
					UtilMisc.toMap("userLogin", userLogin, "organizationPartyId", organizationPartyId, "teleNumber", teleNumber, "amount", amount));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(activateCloudCardAndRechargeOut)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//暂时写死
		if(activateCloudCardAndRechargeOut.containsKey("errorMessage")){
			if("Users have cards in our store".equalsIgnoreCase(activateCloudCardAndRechargeOut.get("errorMessage").toString())){
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUsersHaveCardsInOurStore", locale));
			}
		}

		//修改验证码状态
		sms.set("isValid", "Y");
		try {
			sms.store();
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		return activateCloudCardAndRechargeOut;
	}

	/**
	 * 无卡消费收款
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizByTeleNumberWithdraw(DispatchContext dctx, Map<String, Object> context) {

		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String teleNumber = (String) context.get("teleNumber");
		String cardId = (String) context.get("cardId");
		String organizationPartyId = (String) context.get("organizationPartyId");
		String captcha = (String) context.get("captcha");
		BigDecimal amount = (BigDecimal) context.get("amount");
		String cardCode = (String) context.get("cardCode");

		EntityCondition captchaCondition = EntityCondition.makeCondition(
				EntityCondition.makeCondition("teleNumber", EntityOperator.EQUALS, teleNumber),
				EntityUtil.getFilterByDateExpr(),
				EntityCondition.makeCondition("isValid", EntityOperator.EQUALS,"N"),EntityCondition.makeCondition("smsType", EntityOperator.EQUALS,CloudCardConstant.USER_PAY_CAPTCHA_SMS_TYPE));

		GenericValue sms = null;
		try {
			sms = EntityUtil.getFirst(
					delegator.findList("SmsValidateCode", captchaCondition, null,UtilMisc.toList("-" + ModelEntity.CREATE_STAMP_FIELD), null, false)
					);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGetCAPTCHAFailedError", locale));
		}

		if(UtilValidate.isEmpty(sms)){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardGetCAPTCHAFailedError", locale));
		}

		if(!captcha.equalsIgnoreCase(sms.getString("captcha"))){
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardCaptchaCheckFailedError", locale));
		}

		// 数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logWarning("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能对用户卡进行扫码消费", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}


		// 调用内部 云卡支付服务
		Map<String, Object> cloudCardWithdrawOut;
		try {
			cloudCardWithdrawOut = dispatcher.runSync("cloudCardWithdraw",
					UtilMisc.toMap("organizationPartyId", organizationPartyId, "cardId", cardId, "organizationPartyId",
							organizationPartyId, "amount", amount,"userLogin",userLogin));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		GenericValue partyGroup = null;
		try {
			partyGroup = CloudCardHelper.getPartyGroupByStoreId(organizationPartyId,delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(partyGroup)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		context.put("smsType", CloudCardConstant.USER_PAY_SMS_TYPE);
		context.put("phone", teleNumber);
		context.put("storeName", partyGroup.getString("groupName"));
		context.put("amount", amount);
		context.put("cardCode", cardCode.substring(cardCode.length()-4,cardCode.length()));
		context.put("cardBalance", cloudCardWithdrawOut.get("cardBalance"));
		SmsServices.sendMessage(dctx, context);

		//修改验证码状态
		sms.set("isValid", "Y");
		try {
			sms.store();
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(resourceError, "CloudCardInternalServiceError", locale));
		}

		cloudCardWithdrawOut.put("cardCode", cardCode);
		cloudCardWithdrawOut.put("teleNumber", teleNumber);
		return cloudCardWithdrawOut;
	}

	/**
	 * 无卡消费充值
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizByTeleNumberRecharge(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dctx.getDelegator();
		GenericValue userLogin = (GenericValue) context.get("userLogin");
		Locale locale = (Locale) context.get("locale");

		String teleNumber = (String) context.get("teleNumber");
		String organizationPartyId = (String) context.get("organizationPartyId");
		BigDecimal amount = (BigDecimal) context.get("amount");

		GenericValue customerMap;
		try {
			customerMap = CloudCardHelper.getUserByTeleNumber(delegator,teleNumber);
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

        if (UtilValidate.isEmpty(customerMap)) {
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserNotExistError", locale));
        }

		// 数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logWarning("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能对用户卡进行无卡消费", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}

		context.put("storeId", organizationPartyId);
		context.put("partyId", customerMap.getString("partyId"));
		Map<String, Object> cloudcardsMap = CloudCardQueryServices.myCloudCards(dctx, context);

		//查询该用户在本店的卡
		List<Object> cloudcardList = UtilGenerics.checkList(cloudcardsMap.get("cloudCardList"));
		if(UtilValidate.isEmpty(cloudcardList)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNoCardInTheStore", locale));
		}

		Map<String,Object> cloudCardMap = UtilGenerics.checkMap(cloudcardList.get(0));

		//充值
		Map<String, Object> rechargeCloudCardOutMap;
		try {
			rechargeCloudCardOutMap = dispatcher.runSync("rechargeCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"organizationPartyId", organizationPartyId,
							"cardId", cloudCardMap.get("cardId"),
							"amount", amount));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (!ServiceUtil.isSuccess(rechargeCloudCardOutMap)) {
			return rechargeCloudCardOutMap;
		}

		//查询店铺名称
		GenericValue partyGroup = null;
		try {
			partyGroup = CloudCardHelper.getPartyGroupByStoreId(organizationPartyId,delegator);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(partyGroup)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		//卡号
		String cardCode = (String) cloudCardMap.get("cardCode");

		//发送充值短信
		context.put("smsType", CloudCardConstant.USER_RECHARGE_SMS_TYPE);
		context.put("phone", teleNumber);
		context.put("storeName", partyGroup.getString("groupName"));
		context.put("amount", amount);
		context.put("cardCode", cardCode.substring(cardCode.length()-4,cardCode.length()));
		context.put("cardBalance", rechargeCloudCardOutMap.get("actualBalance"));
		SmsServices.sendMessage(dctx, context);

		//3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("teleNumber", teleNumber);
		result.put("cardName", cloudCardMap.get("cardName"));
		result.put("cardCode", cardCode);
		result.put("amount", amount);
		result.put("cardBalance", rechargeCloudCardOutMap.get("actualBalance"));
		result.put("customerPartyId", customerMap.getString("partyId"));
		return result;
	}

	/**
	 * 我的消息列表
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> listMyNote(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		List<GenericValue> partyNotes = FastList.newInstance();
		try {
			partyNotes = delegator.findList("PartyNoteView2", EntityCondition.makeCondition(UtilMisc.toMap("partyId", organizationPartyId, "isViewed",  "N")),null, UtilMisc.toList("-noteDateTime"), null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("partyNotes", partyNotes);
		return result;
	}

	/**
	 * 获取历史消息
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> listMyHistoryNote(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		List<GenericValue> partyNotes = FastList.newInstance();
		try {
			partyNotes = delegator.findList("PartyNoteView2", EntityCondition.makeCondition(UtilMisc.toMap("partyId", organizationPartyId, "removed",  "N")),null, UtilMisc.toList("-noteDateTime"), null, false);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("partyNotes", partyNotes);
		return result;
	}


	/**
	 * 标记消息为已读
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> readMyNote(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String noteId = (String) context.get("noteId");

		GenericValue partyNote;
		try {
			partyNote = delegator.findByPrimaryKey("PartyNote",  UtilMisc.toMap("partyId", organizationPartyId, "noteId", noteId));
			partyNote.set("isViewed", "Y");
			delegator.store(partyNote);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		Map<String, Object> listMyNoteMap = null;
		try {
			listMyNoteMap = dispatcher.runSync("listMyNote", UtilMisc.toMap("organizationPartyId", organizationPartyId,"userLogin",userLogin));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
        if (!ServiceUtil.isSuccess(listMyNoteMap)) {
            return listMyNoteMap;
        }
		result.put("partyNotes", listMyNoteMap.get("partyNotes"));
		result.put("organizationPartyId", organizationPartyId);
		result.put("noteId", noteId);
		result.put("isViewed", "Y");
		return result;
	}

	/**
	 * 标记消息为已删除
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> deleteMyNote(DispatchContext dctx, Map<String, Object> context) {
		Delegator delegator = dctx.getDelegator();
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String noteId = (String) context.get("noteId");

		GenericValue partyNote;
		try {
			partyNote = delegator.findByPrimaryKey("PartyNote",  UtilMisc.toMap("partyId", organizationPartyId, "noteId", noteId));
			partyNote.set("removed", "Y");
			delegator.store(partyNote);
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		Map<String, Object> listMyNoteMap = null;
		try {
			listMyNoteMap = dispatcher.runSync("listMyNote", UtilMisc.toMap("organizationPartyId", organizationPartyId,"userLogin",userLogin));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
	      if (!ServiceUtil.isSuccess(listMyNoteMap)) {
	            return listMyNoteMap;
	        }
		result.put("partyNotes", listMyNoteMap.get("partyNotes"));
		result.put("organizationPartyId", organizationPartyId);
		result.put("noteId", noteId);
		result.put("removed", "Y");
		return result;
	}

	/**
	 * 商家上传图片
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizUploadStoreInfoImg(DispatchContext dctx, Map<String, Object> context) {
		LocalDispatcher dispatcher = dctx.getDispatcher();
        //Delegator delegator = dctx.getDelegator();
        Locale locale = (Locale) context.get("locale");
		Map<String, Object> result = ServiceUtil.returnSuccess();

        String organizationPartyId = (String) context.get("organizationPartyId");
		GenericValue userLogin = (GenericValue) context.get("userLogin");

		ByteBuffer imageDataBytes = (ByteBuffer) context.get("uploadedFile");// 文件流，必输
        String fileName = (String) context.get("_uploadedFile_fileName");// 文件名，必输
        String contentType = (String) context.get("_uploadedFile_contentType");// 文件mime类型，必输


		try {
			//上传oss
			Map<String, Object> uploadMap = dispatcher.runSync("upload", UtilMisc.toMap("userLogin",userLogin,"uploadedFile", imageDataBytes, "_uploadedFile_fileName", fileName, "_uploadedFile_contentType", contentType));
            if (!ServiceUtil.isSuccess(uploadMap)) {
                return uploadMap;
            }
	        String key = (String) uploadMap.get("key");

			 // 1.CREATE DATA RESOURCE
			Map<String, Object> createDataResourceMap = UtilMisc.toMap("userLogin", userLogin, "partyId", organizationPartyId,
					"dataResourceTypeId", "URL_RESOURCE", "dataCategoryId", "PERSONAL", "dataResourceName", key,
					"mimeTypeId", contentType, "isPublic", "Y", "dataTemplateTypeId", "NONE", "statusId", "CTNT_PUBLISHED",
					"objectInfo", key);
			Map<String, Object> serviceResultByDataResource = dispatcher.runSync("createDataResource",createDataResourceMap);
			if (!ServiceUtil.isSuccess(serviceResultByDataResource)) {
	            return serviceResultByDataResource;
	        }
			String dataResourceId = (String) serviceResultByDataResource.get("dataResourceId");

			// 2.CREATE CONTENT  type=ACTIVITY_PICTURE
			Map<String, Object> createContentMap = UtilMisc.toMap("userLogin", userLogin, "contentTypeId",
					"ACTIVITY_PICTURE", "mimeTypeId", contentType, "dataResourceId", dataResourceId, "partyId", organizationPartyId);
			Map<String, Object> serviceResultByCreateContentMap = dispatcher.runSync("createContent", createContentMap);
            if (!ServiceUtil.isSuccess(serviceResultByCreateContentMap)) {
                return serviceResultByCreateContentMap;
            }
			String contentId = (String) serviceResultByCreateContentMap.get("contentId");

			// 3.CREATE PARTY CONTENT type=STORE_IMG
			Map<String, Object> createPartyContentMap = UtilMisc.toMap("userLogin", userLogin, "partyId", organizationPartyId, "partyContentTypeId", "STORE_IMG", "contentId", contentId);
			Map<String, Object> serviceResultByCreatePartyContentMap = dispatcher.runSync("createPartyContent",createPartyContentMap);
            if (!ServiceUtil.isSuccess(serviceResultByCreatePartyContentMap)) {
                return serviceResultByCreatePartyContentMap;
            }

		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		return result;
	}

	/**
	 * 商家删除图片
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizDeleteStoreInfoImg(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
	    Delegator delegator = dctx.getDelegator();
	    Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

        String organizationPartyId = (String) context.get("organizationPartyId");
        String contentId = (String) context.get("contentId");

        try {
        	List<GenericValue> partyContents= delegator.findByAnd("PartyContent", UtilMisc.toMap("contentId", contentId,"partyId",organizationPartyId));
        	if(UtilValidate.isEmpty(partyContents)){
    			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPictureDoesNotExist", locale));
        	}
        	GenericValue content = delegator.findByPrimaryKey("Content", UtilMisc.toMap("contentId", contentId));
        	String dataResourceId = content.getString("dataResourceId");
			GenericValue dataResource = delegator.findByPrimaryKey("DataResource", UtilMisc.toMap("dataResourceId", dataResourceId));

			String key = dataResource.getString("objectInfo");
			if(UtilValidate.isEmpty(key)){
    			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardPictureDoesNotExist", locale));
			}

			//删除oss文件
        	dispatcher.runSync("delFile", UtilMisc.toMap("userLogin", userLogin,"key", key));

        	//修改content状态
			content.put("statusId", "CTNT_DEACTIVATED");
			content.store();

        	//修改dataResource状态
			dataResource.put("statusId", "CTNT_DEACTIVATED");
			dataResource.store();

		} catch (Exception e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

        //获取店家商铺详细信息
        List<GenericValue> storeInfoImgList = FastList.newInstance();
        try {
        	storeInfoImgList = delegator.findByAnd("PartyContentAndDataResourceDetail", UtilMisc.toMap("partyId", organizationPartyId,"partyContentTypeId", "STORE_IMG","contentTypeId","ACTIVITY_PICTURE","statusId","CTNT_IN_PROGRESS"));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

        //获取oss访问地址
        String ossUrl = EntityUtilProperties.getPropertyValue("cloudcard","oss.url","http://kupang.oss-cn-shanghai.aliyuncs.com/",delegator);
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("storeInfoImgList", storeInfoImgList);
		result.put("ossUrl", ossUrl);
		return result;
	}

	/**
	 * 商家获取已开的卡
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getCloudCardByBiz(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
	    Delegator delegator = dispatcher.getDelegator();
	    Locale locale = (Locale) context.get("locale");
	    String organizationPartyId = (String) context.get("organizationPartyId");
        GenericValue userLogin = (GenericValue) context.get("userLogin");
	    if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)) {
            // 若不是 system userLogin，则需要验证是否是本店的manager
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + organizationPartyId + "的管理人员，不能进行账户流水查询操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

	    List<Map<String, Object>> finAccounts;
        try {
        	//有可能存在授权的卡
        	EntityCondition thruDateEntityCondition = EntityCondition.makeCondition(EntityOperator.OR,EntityCondition.makeCondition("thruDate", EntityOperator.GREATER_THAN, UtilDateTime.nowTimestamp()),EntityCondition.makeCondition("thruDate", EntityOperator.EQUALS, null));

        	EntityCondition cloudCardInfoEntityCondition = EntityCondition.makeCondition(EntityOperator.AND,
                    EntityCondition.makeCondition(UtilMisc.toMap("distributorPartyId", organizationPartyId)),thruDateEntityCondition);
        	Set<String> cloudCardInfoSet = FastSet.newInstance();
        	cloudCardInfoSet.add("cardNumber");
        	cloudCardInfoSet.add("actualBalance");
        	cloudCardInfoSet.add("ownerPartyId");
        	cloudCardInfoSet.add("paymentMethodId");
        	cloudCardInfoSet.add("description");
        	cloudCardInfoSet.add("lastName");
        	 List<GenericValue> finAccountList = delegator.findList("CloudCardInfo",cloudCardInfoEntityCondition, cloudCardInfoSet, UtilMisc.toList("-actualBalance"), null, false);

        	//查询电话号码
        	finAccounts = FastList.newInstance();
        	Map<String, Object> finAccountMap = null;
        	for(GenericValue finAccount : finAccountList){
        		finAccountMap = FastMap.newInstance();
        		String partyId = finAccount.getString("ownerPartyId");
        		List<GenericValue> partyAndTelecomNumbers = delegator.findByAndCache("PartyAndTelecomNumber", UtilMisc.toMap("partyId", partyId, "partyTypeId", "PERSON"));
        		finAccountMap.put("cardNumber", finAccount.getString("cardNumber"));
        		finAccountMap.put("actualBalance", finAccount.getBigDecimal("actualBalance"));
        		finAccountMap.put("ownerPartyId", finAccount.getString("ownerPartyId"));
        		finAccountMap.put("paymentMethodId", finAccount.getString("paymentMethodId"));
        		finAccountMap.put("description", finAccount.getString("description"));
        		finAccountMap.put("lastName", finAccount.getString("lastName"));
        		if(UtilValidate.isNotEmpty(partyAndTelecomNumbers)){
            		GenericValue partyAndTelecomNumber = partyAndTelecomNumbers.get(0);
            		String contactNumber = partyAndTelecomNumber.getString("contactNumber");
            		finAccountMap.put("teleNumber", contactNumber.replaceAll("(\\d{3})\\d{4}(\\d{4})","$1****$2"));
            	}
        		finAccounts.add(finAccountMap);
        	}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("finAccountList", finAccounts);
		return result;
	}


	/**
	 * 商家获取用户消费列表
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> getUserPaymentBybiz(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");

		String organizationPartyId = (String) context.get("organizationPartyId");
		String partyId = (String) context.get("ownerPartyId");
		String type = (String) context.get("type");
		String cardId = (String) context.get("cardId");
		String cardNumber = (String) context.get("cardNumber");

		// 分页相关
        Integer viewIndex =  (Integer) context.get("viewIndex");
        Integer viewSize  = (Integer) context.get("viewSize");

        List<GenericValue> finAccountAndRoles;
    	try {
            GenericValue encryptedGiftCard = delegator.makeValue("FinAccount", UtilMisc.toMap("finAccountCode", cardNumber));
    		delegator.encryptFields(encryptedGiftCard);
    		finAccountAndRoles = delegator.findList("CloudCardInfo",EntityCondition.makeCondition(UtilMisc.toMap("distributorPartyId", organizationPartyId, "finAccountCode",
    				encryptedGiftCard.getString("finAccountCode"))), UtilMisc.toSet("finAccountCode","finAccountId","ownerPartyId"), null, null, true);
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

    	if(UtilValidate.isEmpty(finAccountAndRoles)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNotOurCardCanNotBeQuery", locale));
    	}

		/*Timestamp fromDate =(Timestamp)context.get("fromTime");
        fromDate = UtilDateTime.getMonthStart(fromDate,0);

		Timestamp thruDate =(Timestamp)context.get("thruTime");
		thruDate =  UtilDateTime.getDayStart(thruDate, 1);

        EntityCondition timeConditions = EntityCondition.makeCondition("effectiveDate", EntityOperator.BETWEEN, UtilMisc.toList(fromDate, thruDate));*/

        EntityCondition paymentConditions = null;
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

		List<Map<String,Object>> paymentsList = FastList.newInstance();
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
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("paymentsList", paymentsList);
		result.put("totalPage", totalPage);
		return result;
	}


	/**
	 * 收款方向付款方发起结算请求
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> initiateSettlement(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		//付款方partyId
		String payerPartyId = (String) context.get("payerPartyId");
		//收款方partyId
		String payeePartyId = (String) context.get("payeePartyId");
		//收款金额
		String amount = (String) context.get("amount");
		//paymentId
		String paymentId = (String) context.get("paymentId");

		if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), payeePartyId)) {
            // 若不是 system userLogin，则需要验证是否是本店的manager
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + payeePartyId + "的管理人员，不能进行账户流水查询操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

		//修改payment的催款次数
		int reqCount = 0;
		try {
			reqCount = CloudCardHelper.increaseSettlementReqCount(delegator, paymentId);
		} catch (GenericEntityException e2) {
			Debug.logError(e2.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//发送通知的请求
		GenericValue partyGroup = null;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", payeePartyId));
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(partyGroup)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//发送消息并记录消息
		try {
			//消息内容
			String noteInfo = partyGroup.getString("groupName") + "向你发起一笔" + amount + "元的跨店交易收款结算请求";
			//极光推送消息
			dispatcher.runSync("pushNotifOrMessage", UtilMisc.toMap("userLogin", userLogin, "appType", "biz", "content", noteInfo, "title", "发起结算请求", "sendType", "tag", "tag", payerPartyId));
			//系统记录消息
			dispatcher.runSync("saveMyNote", UtilMisc.toMap("partyId", payerPartyId, "paymentId", paymentId, "noteName", "INITIATE_SETTLEMENT", "noteInfo" ,noteInfo));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("reqCount", reqCount);
		return result;

	}

	/**
	 * 付款方向收款方发起结算请求
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> settlementRequest(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		//付款方partyId
		String payerPartyId = (String) context.get("payerPartyId");
		//收款方partyId
		String payeePartyId = (String) context.get("payeePartyId");
		//收款金额
		String amount = (String) context.get("amount");
		//paymentId
		String paymentId = (String) context.get("paymentId");

		if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), payerPartyId)) {
            // 若不是 system userLogin，则需要验证是否是本店的manager
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + payerPartyId + "的管理人员，不能进行账户流水查询操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

		//发送通知的请求
		GenericValue partyGroup = null;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", payerPartyId));
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(partyGroup)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		//修改payment状态
		// 改为 PMNT_P_NOT_SENT_RCV
		EntityCondition sentCond = EntityCondition.makeCondition(UtilMisc.toMap("statusId", "PMNT_SENT", "paymentId",paymentId));
		try {
			delegator.storeByCondition("Payment", UtilMisc.toMap("statusId", "PMNT_P_NOT_SENT_RCV"),
					EntityCondition.makeCondition(
								// 状态条件：PMNT_SENT 更新为 PMNT_P_NOT_SENT_RCV
								sentCond
							)
				);
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}


		//发送消息并记录消息
		try {
			//消息内容
			String noteInfo = partyGroup.getString("groupName") + "向你发起一笔" + amount + "元的跨店交易付款结算请求";
			//极光推送消息
			dispatcher.runSync("pushNotifOrMessage", UtilMisc.toMap("userLogin", userLogin, "appType", "biz", "content", noteInfo, "title", "发起结算请求", "sendType", "tag", "tag", payeePartyId));
			//系统记录消息
			dispatcher.runSync("saveMyNote", UtilMisc.toMap("partyId", payeePartyId, "paymentId", paymentId, "noteName", "INITIATE_SETTLEMENT", "noteInfo" ,noteInfo));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;

	}

	/**
	 * 收款方对付款方结算确认
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> settlementConfirmation(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		//付款方partyId
		String payerPartyId = (String) context.get("payerPartyId");
		//收款方partyId
		String payeePartyId = (String) context.get("payeePartyId");
		//收款金额
		String amount = (String) context.get("amount");
		//paymentId
		String paymentId = (String) context.get("paymentId");

		//检查payment状态


		if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), payeePartyId)) {
            // 若不是 system userLogin，则需要验证是否是本店的manager
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + payeePartyId + "的管理人员，不能进行账户流水查询操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

		//发送通知的请求
		GenericValue partyGroup = null;
		try {
			partyGroup = delegator.findByPrimaryKey("PartyGroup", UtilMisc.toMap("partyId", payeePartyId));
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if(UtilValidate.isEmpty(partyGroup)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//查询付款方卖卡限额账户
        GenericValue creditLimitAccount = CloudCardHelper.getCreditLimitAccount(delegator, payerPartyId);
        if (UtilValidate.isEmpty(creditLimitAccount)) {
            Debug.logError("商家[" + payerPartyId + "]未配置卖卡额度账户", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardConfigError",
                    UtilMisc.toMap("organizationPartyId", payerPartyId), locale));
        }

        String creditLimitAccountId = (String) creditLimitAccount.get("finAccountId");

        // 后续可能要用到 system用户操作
        GenericValue systemUserLogin = (GenericValue) context.get("systemUserLogin");
        try {
            systemUserLogin = delegator.findByPrimaryKeyCache("UserLogin", UtilMisc.toMap("userLoginId", "system"));
        } catch (GenericEntityException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        // 卖卡限额回冲
        Map<String, Object> createFinAccountAuthOutMap;
        try {
        	//状态是PMNT_P_NOT_SENT_RCV才能确认结算
        	GenericValue payment = delegator.findByPrimaryKey("Payment", UtilMisc.toMap("paymentId", paymentId));
        	if(!"PMNT_P_NOT_SENT_RCV".equals(payment.getString("statusId"))){
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUnableToConfirmSettlement", locale));
        	}

        	if(UtilValidate.isEmpty(payment)){
                return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        	}

            createFinAccountAuthOutMap = dispatcher.runSync("createFinAccountAuth",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "currencyUomId", CloudCardConstant.DEFAULT_CURRENCY_UOM_ID,
                            "finAccountId", creditLimitAccountId, "amount", payment.getBigDecimal("amount").negate(),
                            //FIXME 奇怪的BUG，如果fromDate直接用当前时间戳，
                            // 会导致FinAccountAuth相关的ECA（updateFinAccountBalancesFromAuth）
                            // 服务中，用当前时间进行 起止 时间筛选FinAccountAuth时漏掉了本次刚创建的这条记录，导致金额计算不正确
                            // 所以，这里人为地把fromDate时间提前2秒，让后面的ECA服务能找到本次创建的记录，以正确计算金额。
                            "fromDate", UtilDateTime.adjustTimestamp(UtilDateTime.nowTimestamp(), Calendar.SECOND, -2)));
        } catch (GenericServiceException | GenericEntityException e1) {
            Debug.logError(e1.getMessage(), module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }

        if (ServiceUtil.isError(createFinAccountAuthOutMap)) {
            return createFinAccountAuthOutMap;
        }
        delegator.clearCacheLine(creditLimitAccount);

        // 设置Payment状态为PMNT_CONFIRMED
        Map<String, Object> setPaymentStatusOutMap;
        try {
            setPaymentStatusOutMap = dispatcher.runSync("setPaymentStatus",
                    UtilMisc.toMap("userLogin", systemUserLogin, "locale", locale, "paymentId", paymentId, "statusId", "PMNT_CONFIRMED"));
        } catch (GenericServiceException e1) {
            Debug.logError(e1, module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
        }
        if (ServiceUtil.isError(setPaymentStatusOutMap)) {
            return setPaymentStatusOutMap;
        }

		//发送消息并记录消息
		try {
			//消息内容
			String noteInfo = partyGroup.getString("groupName") + "已收到你一笔" + amount + "元的跨店交易结算款项请求并确认已结算";
			//极光推送消息
			dispatcher.runSync("pushNotifOrMessage", UtilMisc.toMap("userLogin", userLogin, "appType", "biz", "content", noteInfo, "title", "已确认结算", "sendType", "tag", "tag", payerPartyId));
			//系统记录消息
			dispatcher.runSync("saveMyNote", UtilMisc.toMap("partyId", payeePartyId, "paymentId", paymentId, "noteName", "INITIATE_SETTLEMENT", "noteInfo" ,noteInfo));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//清除该payment消息
		try {
			List<GenericValue> partyNotes = delegator.findByAnd("PartyNote", UtilMisc.toMap("paymentId", paymentId));
			for(GenericValue partyNote : partyNotes){
				partyNote.set("isViewed", "Y");
				partyNote.store();
			}
		} catch (GenericEntityException e1) {
			Debug.logError(e1.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		//返回待结算的payment
		Map<String,Object> settlementMap;
		try {
			settlementMap = dispatcher.runSync("bizListNeedSettlement", UtilMisc.toMap("userLogin", userLogin, "organizationPartyId", payeePartyId, "role", "payee"));
		} catch (GenericServiceException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		List<Object> retList = FastList.newInstance();
		int listSize = 0;
		if(UtilValidate.isNotEmpty(settlementMap.get("retList"))){
			retList =  UtilGenerics.checkList(settlementMap.get("retList"));
			listSize = (int) settlementMap.get("listSize");
		}
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("list", retList);
        result.put("listSize", listSize);
		return result;

	}

	/**
	 * 商家与商家之间协议结算时间
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizSetCloudcardSettlementPeriod(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		String partyIdFrom = (String) context.get("partyIdFrom");
		String partyIdTo = (String) context.get("partyIdTo");
		Long week = (Long) context.get("week");
		Long hour = (Long) context.get("hour");

		if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), partyIdFrom)) {
            // 若不是 system userLogin，则需要验证是否是本店的manager
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + partyIdFrom + "的管理人员，不能进行账户流水查询操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

		GenericValue partyIdFromSettlementPeriod = null;
		GenericValue partyIdToSettlementPeriod = null;

		try {
			partyIdFromSettlementPeriod = delegator.findOne("CloudcardSettlementPeriod", false, UtilMisc.toMap("partyIdFrom", partyIdFrom, "partyIdTo", partyIdTo));
			partyIdToSettlementPeriod = delegator.findOne("CloudcardSettlementPeriod", false, UtilMisc.toMap("partyIdFrom", partyIdTo, "partyIdTo", partyIdFrom));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		if (UtilValidate.isEmpty(partyIdFromSettlementPeriod) && UtilValidate.isEmpty(partyIdToSettlementPeriod) ) {
			try {
				GenericValue cloudcardSettlementPeriod = delegator.makeValue("CloudcardSettlementPeriod", UtilMisc.toMap("partyIdFrom", partyIdFrom, "partyIdTo", partyIdTo,"status","N","week",week,"hour",hour));
				cloudcardSettlementPeriod.create();
			} catch (GenericEntityException e) {
				Debug.logError(e.getMessage(), module);
				return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
			}
		}else{
			if(UtilValidate.isNotEmpty(partyIdFromSettlementPeriod)){
				partyIdFromSettlementPeriod.put("week", week);
				partyIdFromSettlementPeriod.put("hour", hour);
				try {
					partyIdFromSettlementPeriod.store();
				} catch (GenericEntityException e) {
					Debug.logError(e.getMessage(), module);
					return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}
			}

			if(UtilValidate.isNotEmpty(partyIdToSettlementPeriod)){
				partyIdToSettlementPeriod.put("Week", week);
				partyIdToSettlementPeriod.put("Hour", hour);
				try {
					partyIdToSettlementPeriod.store();
				} catch (GenericEntityException e) {
					Debug.logError(e.getMessage(), module);
					return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
				}
			}
		}
		Map<String, Object> result = ServiceUtil.returnSuccess();
		return result;
	}

	/**
	 * 商家与商家之间协议结算时间
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizGetCloudcardSettlementPeriod(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
        GenericValue userLogin = (GenericValue) context.get("userLogin");

		String partyIdFrom = (String) context.get("partyIdFrom");
		String partyIdTo = (String) context.get("partyIdTo");

		if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), partyIdFrom)) {
            // 若不是 system userLogin，则需要验证是否是本店的manager
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + partyIdFrom + "的管理人员，不能进行账户流水查询操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

		GenericValue partyIdFromSettlementPeriod = null;
		GenericValue partyIdToSettlementPeriod = null;
		try {
			partyIdFromSettlementPeriod = delegator.findOne("CloudcardSettlementPeriod", true, UtilMisc.toMap("partyIdFrom", partyIdFrom, "partyIdTo", partyIdTo));
			partyIdToSettlementPeriod = delegator.findOne("CloudcardSettlementPeriod", true, UtilMisc.toMap("partyIdFrom", partyIdTo, "partyIdTo", partyIdFrom));
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		long week = 0;
		long hour = 0;
		String status = "N";
		if(UtilValidate.isNotEmpty(partyIdFromSettlementPeriod)){
			week = partyIdFromSettlementPeriod.getLong("week");
			hour = partyIdFromSettlementPeriod.getLong("hour");
			status = "Y";
		}else if(UtilValidate.isNotEmpty(partyIdToSettlementPeriod)){
			week = partyIdToSettlementPeriod.getLong("week");
			hour = partyIdToSettlementPeriod.getLong("hour");
			status = "Y";
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("week", week);
		result.put("hour", hour);
		result.put("status", status);
		return result;
	}

	/**
	 * 我的信用
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> bizGetMyStorelevel(DispatchContext dctx, Map<String, Object> context){
		LocalDispatcher dispatcher = dctx.getDispatcher();
		Delegator delegator = dispatcher.getDelegator();
		Locale locale = (Locale) context.get("locale");
		String storeId = (String) context.get("storeId");

		GenericValue userLogin = (GenericValue) context.get("userLogin");

        // 数据权限检查: 登录用户是否是本店的管理员
        if (!CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), storeId)) {
            Debug.logError("partyId: " + userLogin.getString("partyId") + " 不是商户：" + storeId + "的管理人员，不能操作", module);
            return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
        }

        String storeLevel = null;
        try {
			GenericValue level = CloudCardLevelScoreUtil.getBizLevel(delegator, storeId);
			if(UtilValidate.isNotEmpty(level)){
				storeLevel = level.getString("partyClassificationGroupId");
			}
		} catch (GenericEntityException e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}

		Map<String, Object> result = ServiceUtil.returnSuccess();
			result.put("storeLevel", storeLevel);
		return result;
	}
}
