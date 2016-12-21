package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilValidate;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

/**
 * <p>测试 卡授权 接口</p>
 * <pre>
 * 注意，
 *   1、每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 *   2、父类中的test方法，在每个子类测试的时候都会被执行
 * </pre>
 * @author cy
 * @see CloudCardServicesTest
 */
public class CreateCardAuthTest extends CloudCardServicesTest {

	public CreateCardAuthTest(String name) {
		super(name);
	}

	/**
	 * 测试 卡授权 和 撤销授权
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testCreateCardAuthAndRevokeAuth() throws GenericServiceException, GenericEntityException{

		// 获取用户卡
		Map<String, Object> resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));

		Integer listSize = (Integer) resp.get("listSize");
		assertTrue("should has 1 more cards here", listSize >=1);
		
		List<Map<String,Object>> cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));
		Map<String,Object> oneCard = cloudCardList.get(0);
		
		String cardId = (String) oneCard.get("cardId");
		String distributorPartyId = (String) oneCard.get("distributorPartyId");
		String ownerPartyId = (String) oneCard.get("ownerPartyId");
		String isAuthToMe = (String) oneCard.get("isAuthToMe");
		String isAuthToOthers = (String) oneCard.get("isAuthToOthers");
		BigDecimal cardBalance = (BigDecimal) oneCard.get("cardBalance");
		
		assertEquals("N", isAuthToMe);
		assertEquals("N", isAuthToOthers);

		BigDecimal amount = new BigDecimal(10.00f);

		// 测试1 自己授权给自己应该失败
		resp = callCreateCardAuth(CUSTOMER_1_TEL, cardId, amount, null, null, 2, CUSTOMER_1_TEL);
		assertFalse("Service 'createCardAuth' SHOULD NOT result success while sb auth a card to themselves", ServiceUtil.isSuccess(resp));

		// 测试2 授权给客户2 10元
		resp = callCreateCardAuth(CUSTOMER_1_TEL, cardId, amount, null, null, 2, CUSTOMER_2_TEL);
		assertTrue("Service 'createCardAuth' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		// 查询 客户2的 卡列表, 应该多出一张 别人授权给我 的卡，卡的余额是10.00
		resp = callMyCloudCards(CUSTOMER_2_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		listSize = (Integer) resp.get("listSize");
		assertTrue("should has 1 more cards here", listSize >=1);
		
		List<Map<String,Object>> cloudCardList_2 = UtilGenerics.checkList(resp.get("cloudCardList"));
		
		Map<String,Object> authToMeCard = null;
		for(Map<String,Object> cc: cloudCardList_2){
			String tmp_distributorPartyId = (String) cc.get("distributorPartyId");
			String tmp_ownerPartyId = (String) cc.get("ownerPartyId");
			String tmp_isAuthToMe = (String) cc.get("isAuthToMe");
			String tmp_isAuthToOthers = (String) cc.get("isAuthToOthers");
			BigDecimal tmp_cardBalance = (BigDecimal) cc.get("cardBalance");
			if("Y".equals(tmp_isAuthToMe) && 
					"N".equals(tmp_isAuthToOthers) &&
					distributorPartyId.equals(tmp_distributorPartyId) &&
					tmp_cardBalance.setScale(decimals, rounding).equals(amount.setScale(decimals, rounding)) &&
					ownerPartyId.equals(tmp_ownerPartyId)
					){
				authToMeCard = cc;
				break;
			}
		}

		assertNotNull("authToMeCard SHOULD NOT be null", authToMeCard);
		String authToMeCard_Id = (String) authToMeCard.get("cardId");


		// 查询 客户1的 卡列表, 刚授权出去那张卡的余额应该减少10元，且状态标识为 已授权给他人
		resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));

		listSize = (Integer) resp.get("listSize");
		assertTrue("should has 1 more cards here", listSize >=1);
		
		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));

		Map<String,Object> authToOthersCard = null;
		for(Map<String,Object> cc: cloudCardList){
			String tmp_cardId = (String) cc.get("cardId");
			String tmp_distributorPartyId = (String) cc.get("distributorPartyId");
			String tmp_ownerPartyId = (String) cc.get("ownerPartyId");
			String tmp_isAuthToMe = (String) cc.get("isAuthToMe");
			String tmp_isAuthToOthers = (String) cc.get("isAuthToOthers");
			if(cardId.equals(tmp_cardId) &&
					"N".equals(tmp_isAuthToMe) && 
					"Y".equals(tmp_isAuthToOthers) &&
					distributorPartyId.equals(tmp_distributorPartyId) &&
					ownerPartyId.equals(tmp_ownerPartyId)
					){
				authToOthersCard = cc;
				break;
			}
		}
		assertNotNull("authToOthersCard SHOULD NOT be null", authToOthersCard);
		
		BigDecimal new_cardBalance = (BigDecimal) authToOthersCard.get("cardBalance");
		// 卡授权后，自己的余额应该相应地减少
		assertEquals(
				"The balance should be reduced by the auth amount:" + amount.toPlainString(),
				cardBalance.subtract(amount).setScale(decimals, rounding), 
				new_cardBalance.setScale(decimals, rounding));
		
		// 测试3、 已授权的卡 不能 再次授权
		resp = callCreateCardAuth(CUSTOMER_1_TEL, cardId, amount, null, null, 2, CUSTOMER_2_TEL);
		assertFalse("Service 'createCardAuth' SHOULD NOT result success when the card already auth to others", ServiceUtil.isSuccess(resp));

		// 测试4、 别人授权给我的卡，也不能再次授权
		resp = callCreateCardAuth(CUSTOMER_2_TEL, authToMeCard_Id, amount, null, null, 2, CUSTOMER_1_TEL);
		assertFalse("Service 'createCardAuth' SHOULD NOT result success when the card already auth to others", ServiceUtil.isSuccess(resp));
		
		// 测试5、测试回收授权
		resp = callRevokeCardAuth(CUSTOMER_1_TEL, cardId);
		assertTrue("Service 'revokeCardAuth' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		// 解除授权后 查询 客户2的 卡列表, 应该找不到 刚才那张 别人授权给我 的卡
		resp = callMyCloudCards(CUSTOMER_2_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		
		listSize = (Integer) resp.get("listSize");
		
		boolean findTheAuthToMeCard = false;
		authToMeCard = null;
		
		cloudCardList_2 = UtilGenerics.checkList(resp.get("cloudCardList"));
		if(UtilValidate.isNotEmpty(cloudCardList_2)){
			for(Map<String,Object> cc: cloudCardList_2){
				String tmp_cardId = (String) cc.get("cardId");
				if(tmp_cardId.equals(authToMeCard_Id)){
					findTheAuthToMeCard = true;
					break;
				}
			}
		}
		assertFalse("the customer SHOULD NOT see the card when it was revoked by the owner", findTheAuthToMeCard);

		// 查询 客户1的 卡列表, 刚解除授权那张卡的余额恢复到授权之前，且状态标识为 未授权
		resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		listSize = (Integer) resp.get("listSize");
		assertTrue("should has 1 more cards here", listSize >=1);
		
		cloudCardList = UtilGenerics.checkList(resp.get("cloudCardList"));

		oneCard = null;
		for(Map<String,Object> cc: cloudCardList){
			if(cardId.equals(cc.get("cardId")) ){
				oneCard = cc;
				break;
			}
		}
		assertNotNull("the card SHOULD NOT be null", oneCard);
		
		new_cardBalance = (BigDecimal) oneCard.get("cardBalance");
		assertEquals(
				"The card balance should be equal to the card balance before authorization",
				cardBalance.setScale(decimals, rounding), 
				new_cardBalance.setScale(decimals, rounding));

		assertEquals("The flag: isAuthToMe  SHOULD BE 'N'", "N", oneCard.get("isAuthToMe"));
		assertEquals("The flag: isAuthToOthers  SHOULD BE 'N'", "N", oneCard.get("isAuthToOthers"));

	}

}
