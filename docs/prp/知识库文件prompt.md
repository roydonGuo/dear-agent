完成需求：知识库文件的开发

需求描述：
创建一个知识库系统，模块在module-knowledge，对知识库文件模块进行crud维护，数据库表已经存在，以下是相关的表结构：

create table ai_knowledge_file
(
id            bigint auto_increment comment '标识'
primary key,
base_id       bigint               not null comment '知识库id',
parent_id     bigint       default 0   null comment '上级',
ancestors     text 				 default '0' null comment '祖籍列表',
name          varchar(20)              null comment '名称（可重命名）',
file_type     varchar(6)           not null comment '类型（folder/file）',
mine_type     varchar(32)              null comment '文件格式（md、pdf、jpg、mp4等）',
content 		longtext               null comment '文件内容',
create_time   datetime                 null comment '创建时间',
update_time   datetime                 null comment '更新时间'
)
comment 'knowledge-文件表';

文件表根据parent_id构成树结构，ancestors字段保存父级id列表，例如：0,1。

给出一个查询文件树结构接口，命名为/tree
定义相应结构参考：
```json

{
id: 'f1', name: '快速开始', type: 'folder', expanded: true,
createTime: '', updateTime: '',
children: [
{ id: 'd1', name: '简介.md', type: 'file', fileType: 'md',
content: '# 🤓简介\n\n欢迎使用知识库系统。\n\n## 功能特性\n\n- 即时渲染 Markdown 编辑\n- 文件树管理\n- 分类组织',
  createTime: now(), updateTime: now() },
{ id: 'd2', name: '安装指南.md', type: 'file', fileType: 'md',
content: '# 安装指南',
  createTime: now(), updateTime: now() },
],
}
```
