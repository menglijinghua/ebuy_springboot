package com.lcvc.ebuy_springboot.service.impl;

import com.lcvc.ebuy_springboot.dao.ProductDao;
import com.lcvc.ebuy_springboot.dao.ProductOrderDao;
import com.lcvc.ebuy_springboot.dao.ProductOrderDetailDao;
import com.lcvc.ebuy_springboot.dao.WebConfigDao;
import com.lcvc.ebuy_springboot.model.*;
import com.lcvc.ebuy_springboot.model.base.PageObject;
import com.lcvc.ebuy_springboot.model.exception.MyServiceException;
import com.lcvc.ebuy_springboot.model.exception.MyWebException;
import com.lcvc.ebuy_springboot.model.query.ProductOrderQuery;
import com.lcvc.ebuy_springboot.service.ProductOrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;

@Transactional(propagation = Propagation.REQUIRED,isolation = Isolation.DEFAULT,timeout=36000,rollbackFor=Exception.class)
@Validated//表示开启sprint的校检框架，会自动扫描方法里的@Valid（@Valid注解一般写在接口即可）
@Service
public class ProductOrderServiceImpl implements ProductOrderService {

    @Autowired
    private ProductOrderDao productOrderDao;
    @Autowired
    private ProductOrderDetailDao productOrderDetailDao;
    @Autowired
    private ProductDao productDao;
    @Autowired
    private WebConfigDao webConfigDao;

    /*
     * 生成订单编号（唯一），按规则生成
     * 计算规则：直接使用java API生成
     */
   /* private String getOrderNo(){
        UUID uuid = UUID.randomUUID();
        return UUID.randomUUID().toString().replaceAll("-", "");
    }*/

    /**
     * 生成订单编号（唯一），按照“时间戳_账户ID_随机数”的格式生成
     * 说明：本方法在高并发情况下需要重新设定
     * 如果采用_隔开，则绝对不会重复，除非业务允许当前账户可以同时提交多个订单
     * 为了格式的编号美观，取消_线，同时把随机因子提升。
     */
    private String getOrderNo(@NotNull Integer customerId){
        //获得当前时间戳
        SimpleDateFormat sdf=new SimpleDateFormat("yyyyMMddHHmmss");
        String currentDate=sdf.format(new Date());
        int random=(int)(new Random().nextInt(99999)+100000);//保证范围在100000-199999内，6位数随机
        String orderNo=currentDate+""+customerId+""+random;
        return orderNo;
    }

    /**
     * 将购物车中的信息保存到数据库中，然后清空购物车
     * @param shoppingCart
     * @param productOrder 订单
     */
    private void saveShoppingCart(ShoppingCart shoppingCart,ProductOrder productOrder){
        WebConfig webConfig=webConfigDao.get();//读取网站配置信息
        Integer maxSingleProductNumberByBuy=webConfig.getMaxSingleProductNumberByBuy();
        List<ShoppingCartItem> list=shoppingCart.getList();
        if(list.size()>0){//如果购物车有商品
            ProductOrderDetail productOrderDetail=null;//子订单
            for(ShoppingCartItem item:list){
                Product product=productDao.getSimple(item.getProduct().getId());//从数据库获取最新的产品信息
                if(product!=null){//如果商品存在
                    if(!product.getOnSale()){
                        throw new MyServiceException("操作错误：商品"+product.getName()+"已经下架，请移除该商品");
                    }
                    //检查库存是否足够
                    if(item.getNumber()>product.getRepository()){
                        throw new MyServiceException("操作错误：商品"+product.getName()+"库存不足");
                    }
                    if(maxSingleProductNumberByBuy>0 && item.getNumber()> maxSingleProductNumberByBuy){
                        throw new MyServiceException("操作错误：同一件商品一次不能购买超过"+maxSingleProductNumberByBuy+"件");
                    }
                }else{
                    throw new MyWebException("操作错误：商品不存在");
                }
                //如果商品验证通过
                productOrderDetail=new ProductOrderDetail();
                productOrderDetail.setProductOrder(productOrder);//设置订单编号
                productOrderDetail.setProduct(item.getProduct());//获取产品信息
                productOrderDetail.setOriginalPrice(item.getProduct().getOriginalPrice());//获取产品原价
                productOrderDetail.setPrice(item.getProduct().getPrice());//获取产品现价
                productOrderDetail.setProductNumber(item.getNumber());//获取购买数量
                productOrderDetailDao.save(productOrderDetail);//保存子订单
                product.setRepository(product.getRepository()-item.getNumber());//将产品库存-本次购买数量
                productDao.update(product);
            }
            //清空购物车
            list.clear();
            shoppingCart=null;
        }else{//如果没有商品
            throw new MyWebException("保存失败:请先购买商品再下单");
        }
    }

    /**
     * 将订单中购买的产品返回到产品库存中
     * 用于配合要作废、取消、退货的订单使用
     * @param productOrder 订单
     */
    private void updateProductRepositoryFromProductNumberOfProductOrder(ProductOrder productOrder){
        for(ProductOrderDetail productOrderDetail:productOrder.getProductOrderDetails()){//遍历子订单
            Product product=productDao.getSimple(productOrderDetail.getProduct().getId());//从数据库获取最新的产品信息
            if(product!=null) {//如果商品存在
                product.setRepository(product.getRepository()+productOrderDetail.getProductNumber());//将产品库存+订单购买的商品数量
                productDao.update(product);
            }else{
                throw new MyServiceException("操作错误：商品不存在");
            }
        }
    }

    @Override
    public Integer total() {
        return productOrderDao.total();
    }

    @Override
    public PageObject search(Integer page, Integer limit, ProductOrderQuery productOrderQuery) {
        PageObject pageObject = new PageObject(limit,page,productOrderDao.querySize(productOrderQuery));
        pageObject.setList(productOrderDao.query(pageObject.getOffset(),pageObject.getLimit(),productOrderQuery));
        for(ProductOrder productOrder:(List<ProductOrder>)pageObject.getList()){
            BigDecimal totalPrice=new BigDecimal("0.00");//默认总价
            //处理订单信息
            for(ProductOrderDetail productOrderDetail:productOrder.getProductOrderDetails()){//遍历子订单
                //计算子订单的价格
                BigDecimal priceTotal=productOrderDetail.getPrice().multiply(BigDecimal.valueOf(productOrderDetail.getProductNumber()));
                productOrderDetail.setPriceTotal(priceTotal);
                totalPrice=totalPrice.add(priceTotal);
            }
            productOrder.setTotalPrice(totalPrice);
        }
        return pageObject;
    }

    @Override
    public List<ProductOrder> search(ProductOrderQuery productOrderQuery) {
        List<ProductOrder> list=productOrderDao.readAll(productOrderQuery);
        for(ProductOrder productOrder:list){
            BigDecimal totalPrice=new BigDecimal("0.00");//默认总价
            //处理订单信息
            for(ProductOrderDetail productOrderDetail:productOrder.getProductOrderDetails()){//遍历子订单
                //计算子订单的价格
                BigDecimal priceTotal=productOrderDetail.getPrice().multiply(BigDecimal.valueOf(productOrderDetail.getProductNumber()));
                productOrderDetail.setPriceTotal(priceTotal);
                totalPrice=totalPrice.add(priceTotal);
            }
            productOrder.setTotalPrice(totalPrice);
        }
        return list;
    }

    @Override
    public ProductOrder get(@NotNull String orderNo) {
        ProductOrder productOrder=productOrderDao.get(orderNo);
        BigDecimal totalPrice=new BigDecimal("0.00");//默认总价
        //处理订单信息
        for(ProductOrderDetail productOrderDetail:productOrder.getProductOrderDetails()){//遍历子订单
            //计算子订单的价格
            BigDecimal priceTotal=productOrderDetail.getPrice().multiply(BigDecimal.valueOf(productOrderDetail.getProductNumber()));
            productOrderDetail.setPriceTotal(priceTotal);
            totalPrice=totalPrice.add(priceTotal);
        }
        productOrder.setTotalPrice(totalPrice);
        return productOrder;
    }

    @Override
    public String save(ShoppingCart shoppingCart,ProductOrder productOrder,Customer customer) {
        if(shoppingCart.getList().size()==0){
            throw new MyWebException("保存失败:购物车中的没有商品，请选购商品后再下单");
        }
        if(productOrder.getPaymentType()==null){
            throw new MyWebException("保存失败:请选择支付方式");
        }
        if(productOrder.getSendName()==null){
            throw new MyWebException("保存失败:请输入收货人名字");
        }
        if(productOrder.getSendAddress()==null){
            throw new MyWebException("保存失败:请输入收货人地址");
        }
        if(productOrder.getSendTel()==null){
            throw new MyWebException("保存失败:请输入收货人电话");
        }
        if(productOrder.getSendZip()==null){
            throw new MyWebException("保存失败:请输入收货人邮编");
        }
        WebConfig webConfig=webConfigDao.get();//读取网站配置信息
        Integer maxProductOrderNumberInToday=webConfig.getMaxProductOrderNumberInToday();//获取当天可以购买的最大订单数
        if(maxProductOrderNumberInToday>0){
            ProductOrderQuery productOrderQuery=new ProductOrderQuery();
            productOrderQuery.setCustomer(customer);
            productOrderQuery.setCreateTimeQueryOfCurrentDay(new Date());//查询今天的订单
            int orderNumber=productOrderDao.querySize(productOrderQuery);//获取当天改客户下单的次数
            if(orderNumber>=maxProductOrderNumberInToday){
                throw new MyServiceException("保存失败:每天可以最多只能下"+maxProductOrderNumberInToday+"单，当前已下了"+orderNumber+"单");
            }
        }
        if(productOrder.getPaymentType()==1){//如果是网上支付
            productOrder.setTag(0);//设置为待付款
            productOrder.setDeliverStatus(0);//设置物流状态为未开始
        }else{//如果是货到付款
            productOrder.setTag(1);//设置为待发货
            productOrder.setDeliverStatus(1);//设置物流状态为待发货
        }
        productOrder.setOrderNo(this.getOrderNo(customer.getId()));//获取系统生成的订单编号
        productOrder.setPaymentStatus(0);//设置支付状态为未付款
        productOrder.setCustomer(customer);//设置付款的账户
        productOrder.setCreateTime(new Date());//设置交易时间
        productOrderDao.save(productOrder);//保存订单
        //保存子订单，将购物车的数据拿出来
        this.saveShoppingCart(shoppingCart,productOrder);
        return productOrder.getOrderNo();//返回订单号
    }

    @Override
    public void update(@Valid @NotNull ProductOrder productOrder,@NotNull Admin admin) {
        if(productOrder.getOrderNo()==null){
            throw new MyWebException("编辑失败：缺少订单编号");
        }
        ProductOrder productOrderOriginal =productOrderDao.get(productOrder.getOrderNo());//获取原订单信息
        if(productOrderOriginal==null){
            throw new MyWebException("修改失败:找不到订单");
        }
        if(!productOrderOriginal.allowUpdate()){//已付款的订单不可以修改
            throw new MyServiceException("修改失败：不符合修改条件");
        }
        //productOrder.setStrikePrice(null);//不允许修改成交价。这里允许修改成交价，如果是0则将成交价设置为NULL，如果是大于0则设置为相关价格，如果是NULL则不作修改
        productOrder.setTag(null);//不允许修改订单状态，设置为NULL
        productOrder.setPaymentStatus(null);//不允许修改付款状态
        productOrder.setPaymentType(null);//不允许修改付款方式
        productOrder.setDealTime(null);//不允许修改交易时间
        productOrder.setSendTime(null);//不允许修改发货时间
        productOrder.setReceiveTime(null);//不允许修改收货时间
        productOrderDao.update(productOrder);//修改订单信息
    }


    @Override
    public void update(@Valid @NotNull ProductOrder productOrder,@NotNull Customer customer) {
        if(productOrder.getOrderNo()==null){
            throw new MyWebException("编辑失败：缺少订单编号");
        }
        ProductOrder productOrderOriginal =productOrderDao.get(productOrder.getOrderNo());//获取原订单信息
        if(productOrderOriginal==null){
            throw new MyWebException("修改失败:找不到订单");
        }
        if(productOrderOriginal.getCustomer().getId()!=customer.getId().intValue()){//如果不是客户本人修改的
            throw new MyWebException("修改失败:该订单必须购买者本人才能修改");
        }
        if(!productOrderOriginal.allowUpdate()){//已付款的订单不可以修改
            throw new MyServiceException("修改失败：不符合修改条件");
        }
        productOrder.setTag(null);//不允许修改订单状态，设置为NULL
        productOrder.setPaymentStatus(null);//不允许修改付款状态
        productOrder.setPaymentType(null);//不允许修改付款方式
        productOrder.setStrikePrice(null);//不允许修改成交价
        productOrder.setDealTime(null);//不允许修改交易时间
        productOrder.setSendTime(null);//不允许修改发货时间
        productOrder.setReceiveTime(null);//不允许修改收货时间
        productOrderDao.update(productOrder);//修改订单信息
    }


    @Override
    public void updatePaymentStatusForPay(@Valid @NotNull String OrderNo, @NotNull Customer customer) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getPaymentStatus()>0){
                throw new MyServiceException("修改失败:订单已经付款");
            }
            if(productOrderOriginal.getPaymentType()==2){//如果是货到付款
                throw new MyServiceException("修改失败:货到付款的订单只能线下支付");
            }
            Integer tagOriginal=productOrderOriginal.getTag();//获取原订单状态
            if(tagOriginal!=0){
                throw new MyServiceException("修改失败:订单只有处于待付款状态才能付款");
            }
            if(productOrderOriginal.getCustomer().getId()!=customer.getId().intValue()){//如果不是客户本人修改的
                throw new MyWebException("修改失败:该订单必须购买者本人才能修改");
            }
            if(productOrderOriginal.getStrikePrice()==null){//如果没有成交价，则让成交价=总价
                BigDecimal totalPrice=new BigDecimal("0.00");//默认总价
                //处理订单信息
                for(ProductOrderDetail productOrderDetail:productOrderOriginal.getProductOrderDetails()){//遍历子订单
                    //计算子订单的价格
                    BigDecimal priceTotal=productOrderDetail.getPrice().multiply(BigDecimal.valueOf(productOrderDetail.getProductNumber()));
                    productOrderDetail.setPriceTotal(priceTotal);
                    totalPrice=totalPrice.add(priceTotal);
                }
                productOrderOriginal.setStrikePrice(totalPrice);//成交价=总价
            }
            productOrderOriginal.setPaymentStatus(1);//修改订单状态为已付款
            productOrderOriginal.setDeliverStatus(1);//设置物流状态为待发货
            productOrderOriginal.setDealTime(Calendar.getInstance().getTime());//付款时间
            productOrderOriginal.setTag(1);//修改订单状态为待发货
            productOrderDao.update(productOrderOriginal);//保存到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

   /* @Override
    public void updatePaymentStatusForPay(@Valid @NotNull String OrderNo, @NotNull Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getPaymentStatus()>0){
                throw new MyServiceException("修改失败:订单已经付款");
            }
            if(productOrderOriginal.getPaymentType()==1){//如果是网上支付
                throw new MyServiceException("修改失败:网上支付的订单只能由客户操作");
            }
            Integer tagOriginal=productOrderOriginal.getTag();//获取原订单状态
            if(tagOriginal!=3){
                throw new MyServiceException("修改失败:订单只有处于已收货状态才能付款");
            }
            if(productOrderOriginal.getStrikePrice()==null){//如果没有成交价，则让成交价=总价
                BigDecimal totalPrice=new BigDecimal("0.00");//默认总价
                //处理订单信息
                for(ProductOrderDetail productOrderDetail:productOrderOriginal.getProductOrderDetails()){//遍历子订单
                    //计算子订单的价格
                    BigDecimal priceTotal=productOrderDetail.getPrice().multiply(BigDecimal.valueOf(productOrderDetail.getProductNumber()));
                    productOrderDetail.setPriceTotal(priceTotal);
                    totalPrice=totalPrice.add(priceTotal);
                }
                productOrderOriginal.setStrikePrice(totalPrice);//成交价=总价
            }
            productOrderOriginal.setPaymentStatus(1);//修改订单状态为已付款
            productOrderOriginal.setDealTime(Calendar.getInstance().getTime());//付款时间
            productOrderDao.update(productOrderOriginal);//保存到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }*/

    @Override
    public void updateTagForMerchantShipped(String OrderNo, Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            Integer tagOriginal=productOrderOriginal.getTag();//获取原订单状态
            if(tagOriginal!=1&&productOrderOriginal.getDeliverStatus()!=1){//只有是待发货状态才能发送
                throw new MyServiceException("修改失败:订单必须先处于待发货状态才能发送");
            }
            productOrderOriginal.setTag(2);//修改订单状态为已发货
            productOrderOriginal.setDeliverStatus(2);//设置物流状态为已发货
            productOrderOriginal.setSendTime(Calendar.getInstance().getTime());//发货时间
            productOrderDao.update(productOrderOriginal);//保存到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForReception(String OrderNo, Customer customer) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getCustomer().getId()!=customer.getId().intValue()){//如果不是客户本人修改的
                throw new MyWebException("修改失败:该订单必须购买者本人才能修改");
            }
            Integer tagOriginal=productOrderOriginal.getTag();//获取原订单状态
            if(tagOriginal!=2&&productOrderOriginal.getDeliverStatus()!=2){//只有是待发货状态才能发送
                throw new MyServiceException("修改失败:订单必须先处于待收货状态才能确认收货");
            }
            productOrderOriginal.setTag(3);//修改订单状态为已收货
            productOrderOriginal.setDeliverStatus(3);//设置物流状态为已收货
            if(productOrderOriginal.getPaymentType()==2){//如果是货到付款方式
                productOrderOriginal.setPaymentStatus(1);//付款状态变为已付款
                productOrderOriginal.setDealTime(new Date());//设置交易时间
                if(productOrderOriginal.getStrikePrice()==null){//如果没有成交价，则让成交价=总价
                    BigDecimal totalPrice=new BigDecimal("0.00");//默认总价
                    //处理订单信息
                    for(ProductOrderDetail productOrderDetail:productOrderOriginal.getProductOrderDetails()){//遍历子订单
                        //计算子订单的价格
                        BigDecimal priceTotal=productOrderDetail.getPrice().multiply(BigDecimal.valueOf(productOrderDetail.getProductNumber()));
                        productOrderDetail.setPriceTotal(priceTotal);
                        totalPrice=totalPrice.add(priceTotal);
                    }
                    productOrderOriginal.setStrikePrice(totalPrice);//成交价=总价
                }
            }
            productOrderOriginal.setReceiveTime(Calendar.getInstance().getTime());//收货时间
            productOrderDao.update(productOrderOriginal);//保存到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForComplete(@Valid @NotNull String OrderNo) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getPaymentStatus()!=1){
                throw new MyServiceException("修改失败:该订单还未完成");
            }
            Integer tagOriginal=productOrderOriginal.getTag();//获取原订单状态
            if(tagOriginal!=3&&productOrderOriginal.getDeliverStatus()!=3){//只有是已发货状态才能发送
                throw new MyServiceException("修改失败:订单必须确认收货了才能完成");
            }
            productOrderOriginal.setTag(4);//修改订单状态为已完成
            productOrderDao.update(productOrderOriginal);//保存到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForVoided(@Valid @NotNull String OrderNo) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null) {
            if (productOrderOriginal.getPaymentType() == 1) {//如果是网上支付
                if (productOrderOriginal.getPaymentStatus() != 0 && productOrderOriginal.getTag()!=0) {
                    throw new MyServiceException("修改失败:只有未付款的网上支付类型订单可以作废");
                }
            }
            if (productOrderOriginal.getPaymentType() == 2) {//如果是货到付款
                if (productOrderOriginal.getTag() >= 3 ||productOrderOriginal.getDeliverStatus()==3) {
                    throw new MyServiceException("修改失败:已收货或已完成的订单不可以作废");
                }else if (productOrderOriginal.getTag() <=0) {
                    throw new MyServiceException("修改失败:非正常交易（如取消、退货）的订单不可以作废");
                }
            }
        }
        productOrderOriginal.setTag(-99);//修改为已作废
        productOrderDao.update(productOrderOriginal);//保存到数据库
        //将作废订单的产品返回到库存中
        this.updateProductRepositoryFromProductNumberOfProductOrder(productOrderOriginal);
    }

    @Override
    public void updateTagForCancel(String OrderNo, Customer customer) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getCustomer().getId()!=customer.getId().intValue()){//如果不是客户本人修改的
                throw new MyServiceException("修改失败:该订单必须购买者本人才能修改");
            }
            if(productOrderOriginal.getTag()<0||productOrderOriginal.getTag()>=2){//已发货，已收货的订单和非正常状态订单都不允许取消
                throw new MyServiceException("修改失败:该订单不符合取消条件");
            }
            if (productOrderOriginal.getPaymentType() == 1) {//如果是网上支付
                if (productOrderOriginal.getTag() == 0) {//如果订单未付款状态
                    productOrderOriginal.setTag(-21);//则直接取消订单
                }else  if (productOrderOriginal.getTag() == 1) {//如果订单已经付款状态
                    productOrderOriginal.setTag(-1);//则将订单变为申请取消
                }
            }
            if (productOrderOriginal.getPaymentType() == 2) {//如果是货到付款
                productOrderOriginal.setTag(-1);//则将订单变为申请取消
            }
            if(productOrderOriginal.getTag()==-21){//如果订单已经取消。注意：这里前面必须配合判断，防止已经取消的订单重复取消
                //将取消订单的产品返回到库存中
                this.updateProductRepositoryFromProductNumberOfProductOrder(productOrderOriginal);
            }
            productOrderDao.update(productOrderOriginal);//保存到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForCancel(String OrderNo, Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getTag()!=-1){
                throw new MyServiceException("修改失败:只有申请取消的订单才能取消");
            }
            if(productOrderOriginal.getPaymentStatus()==1){//如果是已经付款，这个是指网上支付的，不过无需再分开进行判断
                productOrderOriginal.setPaymentStatus(2);//设置为已退款状态
            }
            productOrderOriginal.setTag(-21);//取消订单
            //将取消订单的产品返回到库存中
            this.updateProductRepositoryFromProductNumberOfProductOrder(productOrderOriginal);
            productOrderDao.update(productOrderOriginal);//保存订单最新状态到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForRejectCancel(String OrderNo,Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getTag()!=-1){
                throw new MyServiceException("修改失败:只有申请取消的订单才能操作");
            }
            productOrderOriginal.setTag(3);//恢复订单为已经收货状态
            productOrderDao.update(productOrderOriginal);//保存订单最新状态到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForApplyReturn(@NotNull String OrderNo, @NotNull Customer customer) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getTag()!=3){
                throw new MyServiceException("修改失败:只有已确认收货的订单才能申请退货");
            }
            productOrderOriginal.setTag(-2);//申请退货
            productOrderDao.update(productOrderOriginal);//保存订单最新状态到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForAgreeReturn(@NotNull String OrderNo, @NotNull Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getTag()!=-2){
                throw new MyServiceException("修改失败:只有已经申请退货的订单才能操作");
            }
            productOrderOriginal.setTag(-3);//退货中
            productOrderDao.update(productOrderOriginal);//保存订单最新状态到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForConfirmReturn(@NotNull String OrderNo, @NotNull Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getTag()!=-3){
                throw new MyServiceException("修改失败:只有处于退货中的订单才能操作");
            }
            productOrderOriginal.setTag(-22);//已退货
            productOrderDao.update(productOrderOriginal);//保存订单最新状态到数据库
            //将已退货订单的产品返回到库存中
            this.updateProductRepositoryFromProductNumberOfProductOrder(productOrderOriginal);
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

    @Override
    public void updateTagForRejectReturn(@NotNull String OrderNo, @NotNull Admin admin) {
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        if(productOrderOriginal!=null){
            if(productOrderOriginal.getTag()!=-2||productOrderOriginal.getTag()==-3){
                throw new MyServiceException("修改失败:只有申请退货的订单才能操作");
            }
            productOrderOriginal.setTag(-4);//已拒绝退货
            productOrderDao.update(productOrderOriginal);//保存订单最新状态到数据库
        }else{
            throw new MyWebException("修改失败:找不到订单");
        }
    }

  /*  @Override
    public void updateProductOrderTag(@Valid @NotNull String OrderNo, @Valid @NotNull Integer tag) {
        //订单处理状态（-9异常，-2已取消，-1申请取消，0待付款、1.已付款，2已发货，3已收货、4已完成）
        ProductOrder productOrderOriginal =productOrderDao.get(OrderNo);//获取原订单信息
        Integer tagOriginal=productOrderOriginal.getTag();//获取原订单状态
        if(tag==1){//如果要更改为已付款
            if(tagOriginal!=0){
                throw new MyServiceException("修改失败:订单只有处于待付款状态才能付款");
            }
        }else if(tag==2){//如果要更改为已发货
            if(tagOriginal!=1){
                throw new MyServiceException("修改失败:订单只有付款后才能发货");
            }
        }else if(tag==3){//如果要更改为已收货
            if(tagOriginal!=2){
                throw new MyServiceException("修改失败:订单还未发货");
            }
        }else if(tag==-1){//如果要更改为申请取消，即客户申请取消订单
            if(tagOriginal!=1){
                throw new MyServiceException("修改失败:订单还未发货");
            }
        }

    }*/

    public static void main(String[] args) {
        //获得当前时间戳
        SimpleDateFormat sdf=new SimpleDateFormat("yyyyMMddHHmmss");
        String currentDate=sdf.format(new Date());
        int random=(int)(new Random().nextInt(99999)+100000);//保证范围在100000-199999内，6位数随机
        String orderNo=currentDate+""+random+""+32;
        System.out.println(orderNo);
    }
}
