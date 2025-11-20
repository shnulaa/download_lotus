# 🚀 Download Lotus - 高性能多线程下载器

<p align="center">
  <img src="https://img.shields.io/badge/Java-1.8-orange.svg" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7.14-brightgreen.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
</p>

<p align="center">
  <img src="doc/1.png" alt="Screenshot" width="60%" height="30%">
</p>

Download Lotus 是一个基于 Spring Boot 开发的高性能多线程文件下载器，采用 Fork/Join 架构实现动态任务分配和断点续传功能。支持 HTTP/SOCKS 代理，提供可视化的下载进度监控。

## ✨ 核心特性

### 下载功能
- **多线程并行下载** - 支持多线程并发下载，显著提升下载速度
- **Fork/Join 架构** - 使用 ForkJoinPool 实现动态任务分配和 Work Stealing
- **动态 Range 重分配** - 当线程速度过低时自动重新分配下载范围（未完成线程 ≤ 3，速度 < 20%）
- **断点续传** - 支持暂停后继续下载，无需重新开始
- **流式下载支持** - 自动检测是否支持 Range，不支持时使用流式下载

### 代理功能
- **HTTP 代理** - 支持 HTTP/HTTPS 代理配置
- **SOCKS 代理** - 支持 SOCKS4/SOCKS5 代理
- **每任务独立配置** - 不同下载任务可以使用不同的代理

### 监控与可视化
- **实时进度监控** - WebSocket 实时推送每个线程的下载速度和进度
- **400格可视化** - 20×20 网格实时显示下载区块状态
- **多彩线程标识** - 不同线程使用不同颜色，直观展示下载分布
- **速度实时统计** - 每秒更新下载速度和预计剩余时间

### 用户界面
- **前后端分离** - 前端采用 Vue 3 + Element Plus 实现友好的用户界面
- **代码模块化** - CSS、JavaScript 分离，便于维护
- **响应式设计** - 自适应不同屏幕尺寸

### 数据持久化
- **SQLite 数据库** - 轻量级数据库记录下载任务信息
- **任务历史记录** - 保存所有下载任务，支持分页查询
- **自动恢复** - 应用重启后自动恢复未完成的下载

## 🏗️ 技术栈

### 后端
- **JDK 1.8**
- **Spring Boot 2.7.14** - Web、WebSocket、JPA
- **Apache HttpClient 4.5.14** - HTTP 下载客户端，支持代理
- **SQLite** - 轻量级数据库
- **Lombok** - 简化代码
- **FastJSON** - JSON 序列化

### 前端
- **Vue 3** - 渐进式 JavaScript 框架
- **Element Plus** - Vue 3 UI 组件库
- **SockJS + STOMP** - WebSocket 客户端
- **Axios** - HTTP 请求库

## 🚀 快速开始

### 前置要求

- JDK 1.8 或更高版本
- Maven 3.6+

### 本地部署

#### 方式一：使用 Maven

1. **克隆项目**
```bash
git clone https://github.com/shnulaa/download_lotus.git
cd download_lotus
```

2. **编译打包**
```bash
mvn clean package
```

3. **运行应用**
```bash
java -jar target/downloader-1.0.0.jar
```

4. **访问应用**
打开浏览器访问：http://localhost:8081

#### 方式二：开发模式

1. **导入项目到 IDE**
   - IntelliJ IDEA 或 Eclipse
   - 作为 Maven 项目导入

2. **运行主类**
   - 主类：`com.example.downloader.DownloaderApplication`

3. **访问前端**
   - 应用内置了前端页面，访问：http://localhost:8081

## 📖 使用说明

### 创建下载任务

1. 点击**"新建任务"**按钮
2. 填写下载信息：
   - **URL**：文件的 HTTP/HTTPS 下载链接
   - **线程数**：并发下载线程数（1-32，推荐 8）
3. （可选）配置代理：
   - **代理类型**：无代理 / HTTP / SOCKS
   - **代理服务器**：如 127.0.0.1
   - **代理端口**：如 1080
4. 点击**"开始下载"**

### 管理下载任务

- **暂停** - 暂停当前下载，进度会自动保存
- **继续** - 恢复暂停的任务（断点续传）
- **删除** - 删除任务记录（可选删除本地文件）
- **取回本地** - 下载完成后，点击可下载到本地
- **复制链接** - 复制原始下载链接

### 查看下载进度

#### 列表视图
- 文件名和下载状态
- 已下载大小 / 总大小
- 下载进度百分比
- 实时下载速度

#### 区块可视化
点击**"查看区块"**可以看到：
- **20×20 网格**展示下载进度
- 不同颜色代表不同下载线程
- 颜色深浅表示下载状态：
  - 深色：已完成
  - 亮色：正在下载
  - 灰色：等待下载

### 代理使用场景

- 需要通过代理服务器下载文件
- 突破网络限制
- 使用 SOCKS 代理下载

## 🏛️ 项目结构

```
download/
├── db/                          # 数据库文件目录
│   └── downloader.db           # SQLite 数据库
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/downloader/
│       │       ├── config/      # 配置类
│       │       ├── controller/  # 控制器
│       │       ├── core/        # 核心下载逻辑
│       │       ├── entity/      # 实体类
│       │       └── repository/  # 数据访问层
│       └── resources/
│           ├── static/
│           │   ├── css/
│           │   │   ├── app.css          # 应用样式
│           │   │   └── element-plus.css
│           │   ├── js/
│           │   │   ├── app.js           # 应用逻辑
│           │   │   ├── vue.global.js
│           │   │   ├── element-plus.js
│           │   │   ├── axios.min.js
│           │   │   ├── sockjs.min.js
│           │   │   └── stomp.min.js
│           │   └── index.html           # 主页面
│           └── application.yml  # 应用配置
└── Temp/                        # 默认下载目录
```

## 🔧 配置说明

### 应用配置

配置文件：`src/main/resources/application.yml`

```yaml
spring:
  datasource:
    url: jdbc:sqlite:db/downloader.db  # 数据库路径
    driver-class-name: org.sqlite.JDBC
  jpa:
    database-platform: com.example.downloader.config.SQLiteDialect
    hibernate:
      ddl-auto: update
    show-sql: false

server:
  port: 8081  # 应用端口

logging:
  level:
    root: INFO
    com.example.downloader: INFO
```

### 代理配置

#### HTTP 代理
- 使用 Apache HttpClient 原生支持
- 在创建任务时配置代理类型、主机和端口
- 适用于 HTTP/HTTPS 下载

#### SOCKS 代理
- 通过 JVM 系统属性配置
- 支持 SOCKS4 和 SOCKS5
- 注意：SOCKS 代理配置是全局性的

### 线程池配置

在 `DownloadTaskContext.java` 中可以配置：

```java
// 并行度设为 32，避免 IO 阻塞导致线程耗尽
this.chunkExecutor = new ForkJoinPool(32);
```

## 📊 核心算法

### 动态 Range 重分配

当满足以下条件时，系统会自动触发 Range 重分配：
- 未完成下载的线程数量 ≤ 3 个
- 某个线程的下载速度 < 当前最大速度的 20%

重分配逻辑：
1. 识别慢速线程
2. 将其剩余下载范围一分为二
3. Fork 出新的下载任务
4. 两个线程并行完成原任务

这个机制有效解决了多线程下载中的"长尾效应"。

### 断点续传

- 每秒更新每个线程的下载进度到数据库
- 暂停时保存当前 `current` 位置
- 恢复时从 `current` 继续下载
- 支持应用重启后恢复下载

## 🎨 前端特性

### 代码分离
- **HTML** - 只包含页面结构和 Vue 模板
- **CSS** - `css/app.css` 包含所有自定义样式
- **JavaScript** - `js/app.js` 包含所有应用逻辑

### 实时更新
- WebSocket 推送任务状态
- 每秒更新下载速度和进度
- 无需手动刷新页面

### 交互设计
- Element Plus 组件库
- 流畅的动画效果
- 友好的错误提示

## 📈 性能优化

### 下载性能
- 多线程并行下载
- ForkJoinPool Work Stealing
- 动态任务重分配
- 缓冲区大小：8KB

### 网络优化
- HTTP Keep-Alive 连接复用
- 超时设置：连接 10s，读取 30s
- 自动重试机制（最多 5 次）

### 界面性能
- WebSocket 批量推送
- 虚拟滚动（如需要）
- 按需渲染可视化网格

## 🐛 常见问题

### 下载速度慢
- 增加线程数（推荐 8-16）
- 检查网络带宽
- 查看服务器限速

### 无法下载
- 检查 URL 是否有效
- 验证网络连接
- 查看日志错误信息

### 代理无法连接
- 确认代理服务器地址和端口正确
- 检查代理服务器是否可用
- 查看防火墙设置

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [Vue.js](https://vuejs.org/)
- [Element Plus](https://element-plus.org/)
- [Apache HttpClient](https://hc.apache.org/)

## 📞 支持

如有问题或建议，请通过 [GitHub Issues](https://github.com/shnulaa/download_lotus/issues) 联系我们。
