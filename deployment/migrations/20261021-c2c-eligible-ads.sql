-- 广告列表和价格预警共用的基础资格查询；普通视图每次读取原表事实，不保存第二份业务状态。
-- 每个关联均为唯一身份或唯一有效质押，付款方式用EXISTS，保持每个ad_id至多一行。
-- 当前查看者、屏蔽关系、价格/统计筛选仍由各查询的参数限定。
CREATE OR REPLACE VIEW c2c_eligible_ads AS
SELECT a.*
FROM c2c_ads a
JOIN c2c_configuration cfg ON cfg.singleton AND cfg.enabled
JOIN c2c_markets m ON m.market_id=a.market_id AND m.enabled
JOIN assets x ON x.asset=m.asset AND x.listed AND x.trading_enabled
JOIN c2c_merchants c ON c.user_id=a.maker_user_id AND c.status='APPROVED'
JOIN c2c_merchant_bonds b ON b.user_id=c.user_id AND b.status='LOCKED'
JOIN c2c_merchant_bond_policy bp ON bp.asset=b.asset AND b.amount_units>=bp.amount_units
JOIN gateway_users u ON u.user_id=a.maker_user_id AND u.status='NORMAL'
LEFT JOIN gateway_user_kyc_profiles k ON k.user_id=a.maker_user_id
WHERE a.status='ACTIVE' AND a.available_units>0
  AND ROUND(a.available_units::numeric*a.price/x.scale_units,m.fiat_decimals)>=a.min_fiat
  AND (NOT m.require_kyc OR (k.status='VERIFIED' AND (k.expires_at IS NULL OR k.expires_at>now())))
  AND EXISTS(SELECT 1 FROM c2c_payment_methods pm
      WHERE pm.enabled AND pm.code=ANY(a.method_codes) AND m.fiat=ANY(pm.currencies));

COMMENT ON VIEW c2c_eligible_ads IS 'C2C列表/价格预警共用的基础可用广告：市场与资产开关、商家、锁定质押与政策、账户、KYC、库存最低限额、有效付款方式；只读原表，不含查看者屏蔽/价格/统计筛选';
