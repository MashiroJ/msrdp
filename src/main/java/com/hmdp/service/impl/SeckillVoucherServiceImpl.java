package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 秒杀优惠券表，与优惠券是一对一关系 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1、查询优惠券
        SeckillVoucher voucher = getById(voucherId);
        //2、判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀活动尚未开始");
        }

        //3、判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀活动已经结束");
        }

        //4、判断库存是否充足
        Integer stock = voucher.getStock();
        if (stock < 1) {
            return Result.fail("库存不足！");
        }
        //5、一人一单：根据当前用户id和优惠券id判断是否已经下过单
        long userId = UserHolder.getUser().getId();
//        LambdaQueryWrapper<VoucherOrder> queryWrapper = new LambdaQueryWrapper<>();
//        queryWrapper.eq(VoucherOrder::getUserId, userId).eq(VoucherOrder::getVoucherId, voucherId);
//        int count = voucherOrderMapper.selectCount(queryWrapper);  // 或者使用合适的方法来获取计数
//        if (count > 0) {
//            return Result.fail("用户已经购买过一次！");
//        }
        //6、扣减库存
        /*
        使用乐观锁解决超卖问题（CAS法：用数据本身是否发生变化判断线程是否安全）
        修改扣减库存操作，在执行update语句时添加判断，
        判断当前库存与之前查询出来的库存是否相等，
        若相等，则说明没有人在中间修改过库存，那么此时就是安全的。
        boolean isSuccess = update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .eq("stock", voucher.getStock()) //where id = ? and stock = ?
                .update(); */
        //优化乐观锁，当执行update语句时，只需判断当前库存大于0即可。
        boolean isSuccess = update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) //where id = ? and stock = ?
                .update();
        if (!isSuccess) {
            return Result.fail("库存不足！");
        }

        //7、创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1、设置订单id，使用Reids的id生成器
        long orderId = redisIdWorker.nextId("voucherOrder");
        voucherOrder.setId(orderId);
        //7.2、设置优惠券id
        voucherOrder.setVoucherId(voucherId);
        //7.3、设置用户id
        voucherOrder.setUserId(userId);
        voucherOrderMapper.insert(voucherOrder);

        //8、返回订单id
        return Result.ok(orderId);
    }
}
