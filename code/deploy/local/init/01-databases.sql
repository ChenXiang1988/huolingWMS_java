-- 本地三库分离：运营端、单仓端、计费域各一个库，互不共库（01、02）。
-- 单仓端线上是「每仓一套独立库」，本地用一个库代表一个仓。
create database wms_hub;
create database wms_site;
create database wms_billing;
