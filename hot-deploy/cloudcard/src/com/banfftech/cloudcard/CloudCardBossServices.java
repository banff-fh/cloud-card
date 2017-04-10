package com.banfftech.cloudcard;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.DispatchContext;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.LocalDispatcher;
import org.ofbiz.service.ServiceAuthException;
import org.ofbiz.service.ServiceUtil;
import org.ofbiz.service.ServiceValidationException;

import com.banfftech.cloudcard.constant.CloudCardConstant;
import com.banfftech.cloudcard.sms.SmsServices;

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
     */
    public static Map<String, Object> bizSettlementRequest(DispatchContext dctx, Map<String, Object> context) {
        LocalDispatcher dispatcher = dctx.getDispatcher();
        Delegator delegator = dispatcher.getDelegator();
        Locale locale = (Locale) context.get("locale");
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
		String amount = (String) context.get("amount");
		context.put("amount", amount);
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
		Map<String, Object> result = FastMap.newInstance();
		try {
		String partyId = customer.getString("partyId");
		context.put("partyId",partyId );
		result = CloudCardQueryServices.myCloudCards(dctx, context);
		} catch (Exception e) {
			Debug.logError(e.getMessage(), module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		
		result.put("teleNumber", teleNumber);
		result.put("amount", amount);
		return result;
	}
	
	/**
	 * 无卡消费收款
	 * @param dctx
	 * @param context
	 * @return
	 */
	public static Map<String, Object> receiptByCardId(DispatchContext dctx, Map<String, Object> context) {

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
		context.put("storeName", partyGroup.getString("partyName"));
		context.put("amount", amount);
		SmsServices.sendMessage(dctx, context);
		
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
	public static Map<String, Object> rechargeCloudCardByCardId(DispatchContext dctx, Map<String, Object> context) {
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
		
		// 数据权限检查，先放这里
		if( !CloudCardHelper.isManager(delegator, userLogin.getString("partyId"), organizationPartyId)){
			Debug.logWarning("partyId: " + userLogin.getString("partyId") + " 不是商户："+organizationPartyId + "的管理人员，不能对用户卡进行扫码消费", module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardUserLoginIsNotManager", locale));
		}
		
		context.put("storeId", organizationPartyId);
		context.put("partyId", customerMap.getString("partyId"));
		Map<String, Object> cloudcardsMap = CloudCardQueryServices.myCloudCards(dctx, context);
		if(UtilValidate.isEmpty(cloudcardsMap)){
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardNoCardInTheStore", locale));
		}
		
		//查询该用户在本店的卡
		List<Object> cloudcardList = (List<Object>) cloudcardsMap.get("cloudCardList");
		Map<String,Object> cloudCardMap = (Map<String, Object>) cloudcardList.get(0);
		
		//充值
		Map<String, Object> rechargeCloudCardOutMap;
		try {
			rechargeCloudCardOutMap = dispatcher.runSync("rechargeCloudCard",
					UtilMisc.toMap("userLogin", userLogin, "locale",locale,
							"organizationPartyId", organizationPartyId, 
							"cardId", cloudCardMap.get("cardId"),
							"amount", amount));
		} catch (GenericServiceException e1) {
			Debug.logError(e1, module);
			return ServiceUtil.returnError(UtilProperties.getMessage(CloudCardConstant.resourceError, "CloudCardInternalServiceError", locale));
		}
		if (ServiceUtil.isError(rechargeCloudCardOutMap)) {
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
		
		
		//发送充值短信
		context.put("smsType", CloudCardConstant.USER_RECHARGE_SMS_TYPE);
		context.put("phone", teleNumber);
		context.put("storeName", partyGroup.getString("partyName"));
		context.put("amount", amount);
		SmsServices.sendMessage(dctx, context);
		
		//3、返回结果
		Map<String, Object> result = ServiceUtil.returnSuccess();
		result.put("teleNumber", rechargeCloudCardOutMap.get("teleNumber"));
		result.put("cardCode", cloudCardMap.get("finAccountCode"));
		result.put("amount", amount);
		result.put("cardBalance", rechargeCloudCardOutMap.get("actualBalance"));
		result.put("customerPartyId", customerMap.getString("partyId"));
		result.put("cardId", cloudCardMap.get("cardId"));
		return result;
	}
	
}
