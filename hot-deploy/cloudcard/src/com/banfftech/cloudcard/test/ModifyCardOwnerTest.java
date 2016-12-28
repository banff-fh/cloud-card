package com.banfftech.cloudcard.test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

/**
 * <p>测试 卡转让 接口</p>
 * <pre>
 * 注意，
 *   1、每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 *   2、父类中的test方法，在每个子类测试的时候都会被执行
 * </pre>
 * @author cy
 * @see CloudCardServicesTest
 */
public class ModifyCardOwnerTest extends CloudCardServicesTest {

	public ModifyCardOwnerTest(String name) {
		super(name);
	}

	/**
	 * 测试 卡转让服务
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testmodifyCardOwner() throws GenericServiceException, GenericEntityException{

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
		String cardCode = (String) oneCard.get("cardCode");
		BigDecimal cardBalance = ((BigDecimal) oneCard.get("cardBalance")).setScale(decimals, rounding);


		// 测试1 自己转让给自己应该失败
		resp = callModifyCardOwner(CUSTOMER_1_TEL, cardId, CUSTOMER_1_TEL);
		assertFalse("Service 'modifyCardOwner' SHOULD NOT result success while sb transferred a card to themselves", ServiceUtil.isSuccess(resp));

		// 测试2 转让给客户2

		// 转让前先查询客户1、2的卡列表
		resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		int customer1_card_listSize_pre = (Integer) resp.get("listSize");
		List<Map<String,Object>> customer1_card_list_pre = UtilGenerics.checkList(resp.get("cloudCardList"));
		
		resp = callMyCloudCards(CUSTOMER_2_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		int customer2_card_listSize_pre = (Integer) resp.get("listSize");
		List<Map<String,Object>> customer2_card_list_pre = UtilGenerics.checkList(resp.get("cloudCardList"));

		// 调用转让服务
		resp = callModifyCardOwner(CUSTOMER_1_TEL, cardId, CUSTOMER_2_TEL);
		assertTrue("Service 'modifyCardOwner' SHOULD result success", ServiceUtil.isSuccess(resp));

		String newCardId = (String) resp.get("newCardId");
		String newOwnerPartyId = (String) resp.get("customerPartyId");

		// 查询 客户1、2的 卡列表, 客户2应该多出一张 别人转让给他 的卡， 客户1 应该少一张卡
		resp = callMyCloudCards(CUSTOMER_1_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		int customer1_card_listSize_post = (Integer) resp.get("listSize");
		List<Map<String,Object>> customer1_card_list_post = UtilGenerics.checkList(resp.get("cloudCardList"));
		
		resp = callMyCloudCards(CUSTOMER_2_TEL, 0, 20);
		assertTrue("Service 'myCloudCards' SHOULD result success", ServiceUtil.isSuccess(resp));
		int customer2_card_listSize_post = (Integer) resp.get("listSize");
		List<Map<String,Object>> customer2_card_list_post = UtilGenerics.checkList(resp.get("cloudCardList"));

		assertEquals("customer1 's card count should subtract 1", customer1_card_listSize_pre - 1, customer1_card_listSize_post);
		assertEquals("customer2 's card count should add 1", customer2_card_listSize_pre + 1, customer2_card_listSize_post);
		
		// 客户1少的那张卡
		List<Map<String,Object>> customer1_card_list_sub = UtilGenerics.checkList(CollectionUtils.subtract(customer1_card_list_pre, customer1_card_list_post));
		Map<String,Object> oneCard_sub = customer1_card_list_sub.get(0);
		String sub_cardId = (String) oneCard_sub.get("cardId");
		String sub_distributorPartyId = (String) oneCard_sub.get("distributorPartyId");
		String sub_ownerPartyId = (String) oneCard_sub.get("ownerPartyId");
		String sub_cardCode = (String) oneCard_sub.get("cardCode");
		BigDecimal sub_cardBalance = ((BigDecimal) oneCard_sub.get("cardBalance")).setScale(decimals, rounding);
		

		// 客户2多出来的那张卡
		List<Map<String,Object>> customer2_card_list_add = UtilGenerics.checkList(CollectionUtils.subtract(customer2_card_list_post, customer2_card_list_pre));
		Map<String,Object> oneCard_add = customer2_card_list_add.get(0);
		String add_cardId = (String) oneCard_add.get("cardId");
		String add_distributorPartyId = (String) oneCard_add.get("distributorPartyId");
		String add_ownerPartyId = (String) oneCard_add.get("ownerPartyId");
		String add_cardCode = (String) oneCard_add.get("cardCode");
		BigDecimal add_cardBalance = ((BigDecimal) oneCard_add.get("cardBalance")).setScale(decimals, rounding);

		// 断言： cardBalance 不会变化
		assertEquals( cardBalance, sub_cardBalance);
		assertEquals( cardBalance, add_cardBalance);

		// 断言： cardId 会产生一个新的
		assertEquals( cardId, sub_cardId);
		assertEquals( newCardId, add_cardId);
		assertFalse("cardId should be not equals", sub_cardId.equals(add_cardId));
		
		// 断言： distributorPartyId 不变
		assertEquals( distributorPartyId, sub_distributorPartyId);
		assertEquals( distributorPartyId, add_distributorPartyId);
		
		// 断言： ownerPartyId 不应当相等，
		assertEquals( ownerPartyId, sub_ownerPartyId);
		assertEquals( newOwnerPartyId, add_ownerPartyId);
		assertFalse("ownerPartyId should be not equals", sub_ownerPartyId.equals(add_ownerPartyId));

		// 断言： cardCode 不应当相等，
		assertEquals( cardCode, sub_cardCode);
		assertFalse("cardCode should be not equals", sub_cardCode.equals(add_cardCode));

	}

}
