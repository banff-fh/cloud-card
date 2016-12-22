package com.banfftech.cloudcard.test;

import java.util.List;
import java.util.Map;

import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.service.GenericServiceException;
import org.ofbiz.service.ServiceUtil;

/**
 * <p>测试为商家创建卡</p>
 * 
 * <pre>
 * 注意，
 *   1、每个test打头的方法可算是一个 testCase，但是 这些case执行的顺序并不固定，所以各个test方法之间不应该有数据的依赖
 *   2、父类中的test方法，在每个子类测试的时候都会被执行
 * </pre>
 * @author cy
 * @see CloudCardServicesTest
 */
public class CreateNewCardTest extends CloudCardServicesTest {

	public CreateNewCardTest(String name) {
		super(name);
	}

	/**
	 * 测试 生成云卡的服务
	 * @throws GenericServiceException
	 * @throws GenericEntityException
	 */
	public void testGenNewCloudCardCode() throws GenericServiceException, GenericEntityException{

		// 生成15张新卡
		int count = 15;
		Map<String, Object> resp = callGenNewCloudCardCode(STORE_ID_1, "测试商店一的卡15张", count);
		assertTrue("Service 'genNewCloudCardCode' for testStore1 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该只有15张
		List<GenericValue> cloudCardList = queryCardForStoreFromDB(STORE_ID_1, "测试商店一的卡15张", "FNACT_CREATED");
		assertEquals("this service call for testStore1 should generate " + count + " cards", count, cloudCardList.size());

		// 为店家二生成100张新卡
		count = 100;
		resp = callGenNewCloudCardCode(STORE_ID_2, "测试商店二的卡", count);
		assertTrue("Service 'genNewCloudCardCode' for testStore2 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该有100张
		cloudCardList = queryCardForStoreFromDB(STORE_ID_2, "测试商店二的卡", "FNACT_CREATED");
		assertEquals("this service call for testStore2 should generate " + count + " cards", count, cloudCardList.size());

		// 不存在的店家服务应该调用失败
		count = 1;
		resp = callGenNewCloudCardCode(STORE_ID_NA_, "不存在的店家不应该出现的卡", count);
		assertFalse("Service 'genNewCloudCardCode' for testStore_NA_  SHOULD NOT result success", ServiceUtil.isSuccess(resp));	


		// 再为商店二生成30张卡
		count = 30;
		resp = callGenNewCloudCardCode(STORE_ID_2, "测试商店二的卡", count);
		assertTrue("Service 'genNewCloudCardCode' for testStore2 SHOULD result success", ServiceUtil.isSuccess(resp));

		// 查询生成的卡条数，应该有100张+30张 = 130张
		cloudCardList = queryCardForStoreFromDB(STORE_ID_2, "测试商店二的卡", "FNACT_CREATED");
		assertEquals("testStore2 should has 130 cards now!", 130, cloudCardList.size());

	}
}
