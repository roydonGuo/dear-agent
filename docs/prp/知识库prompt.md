完成需求：知识库的开发

需求描述：
创建一个知识库系统，模块在module-knowledge，对知识库进行crud维护，以下是相关的两个表：

1、知识库表结构：
create table ai_knowledge_base
(
id            bigint auto_increment comment '标识'
primary key
name          varchar(20)              null comment '名称',
description   varchar(500)             null comment '描述',
cover_path    text             			   null comment '封面，存储到minio的路径',
category_ids  text             		  	 null comment '分类ids',
create_time   datetime                 null comment '创建时间',
update_time   datetime                 null comment '更新时间'
)
comment 'knowledge-知识库表';

2、知识库分类表：
create table ai_knowledge_category
(
id            bigint auto_increment comment '标识'
primary key
name          varchar(20)              null comment '名称',
icon   				varchar(32)              null comment '分类图标名称（lucide 图标类名）',
sort          int     default 0    not null comment '排序优先级',
create_time   datetime                 null comment '创建时间',
update_time   datetime                 null comment '更新时间'
)
comment 'knowledge-分类表';

项目结构参考：
├── module-knowledge
├── ├── controller
├── ├── mapper // 数据库映射
├── ├── domain // 领域
├── ├── ├── entity // 数据库实体
├── ├── ├── ├── convertor // 实体转换器
├── ├── ├── req // 数据传输对象
├── ├── ├── resp // 数据响应对象
├── ├── service
├── ├── ├── cache // jetcache 二级缓存 适配层
├── ├── ├── impl // 服务接口实现
硬性规范：
0、数据库实体对应实体类对象命名规范：去除ai_表前缀，id一般为ASSIGN_ID，且实体类加上DO后缀表示数据库实体
@Table(name = "ai_knowledge_base")
@Data
class KnowledgeBaseDO implements Serializable {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

}
1、对于实体类对象的转换使用实体类转换框架 MapStruct，且要在实体领域内convertor包下封装转换逻辑，例如：
@Mapper(nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface KnowledgeBaseConvertor {}
2、cache包下为jetcache 二级缓存 适配层，例如有一个 IKnowledgeBaseService 对应的实现类:

KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBaseDO> implements IKnowledgeBaseService {

cache包下就需要对应有一个 IKnowledgeBaseCacheService 接口，该接口继承 IKnowledgeBaseService，并实现对应的缓存逻辑，例如：
需要定义缓存name常量：
public static final String CACHE_NAME = ":knowledge_base:"; // 缓存主名称，下划线分割业务名

public static final String CACHE_NAME_ID = "id:"; // 缓存名称，下划线分割业务名

然后实现缓存逻辑：例如：
@Cached(name = CACHE_NAME + CACHE_NAME_ID, key = "#id", cacheType = CacheType.BOTH, cacheNullValue = true, expire = 60, localExpire = 10, timeUnit = TimeUnit.MINUTES)
@CacheRefresh(refresh = 50, timeUnit = TimeUnit.MINUTES)
public KnowledgeBaseResp findKnowledgeBaseById(Long id) {
// 在此处完成调用IKnowledgeBaseService的实现逻辑，且通过convertor转为KnowledgeBaseResp对象
}

相对的还要有缓存制空的方法：
@CacheInvalidate(name = CACHE_NAME + CACHE_NAME_ID, key = "#id")
public void deleteKnowledgeBaseById(Long id) {}

那么现在你可以将这两个表的相关crud业务补全了
