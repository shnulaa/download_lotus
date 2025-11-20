## HTML前端代理功能修改指南

### 修改1: 更新form对象（第237行）

将：
```javascript
const form = ref({ url: 'https://officecdn-microsoft-com.akamaized.net/pr/C1297A47-86C4-4C1F-97FA-950631F94777/MacAutoupdate/Microsoft_Office_16.55.21111400_BusinessPro_Installer.pkg', threads: 8 });
```

替换为：
```javascript
const form = ref({ 
    url: 'https://officecdn-microsoft-com.akamaized.net/pr/C1297A47-86C4-4C1F-97FA-950631F94777/MacAutoupdate/Microsoft_Office_16.55.21111400_BusinessPro_Installer.pkg', 
    threads: 8,
    proxyType: '',
    proxyHost: '',
    proxyPort: null
});
```

### 修改2: 添加代理UI（在第213行之后）

在这行之后：
```html
                <el-form-item label="线程数">
                    <el-slider v-model="form.threads" :min="1" :max="32" show-input></el-slider>
                </el-form-item>
```

添加以下代码：
```html
                
                <!-- 代理设置 -->
                <el-divider content-position="left">代理设置 (可选)</el-divider>
                <el-form-item label="代理类型">
                    <el-select v-model="form.proxyType" placeholder="请选择" clearable style="width: 100%;">
                        <el-option label="无代理" value=""></el-option>
                        <el-option label="HTTP" value="HTTP"></el-option>
                        <el-option label="SOCKS" value="SOCKS"></el-option>
                    </el-select>
                </el-form-item>
                <el-form-item label="代理服务器" v-if="form.proxyType">
                    <el-input v-model="form.proxyHost" placeholder="例如: 127.0.0.1"></el-input>
                </el-form-item>
                <el-form-item label="代理端口" v-if="form.proxyType">
                    <el-input-number v-model="form.proxyPort" :min="1" :max="65535" placeholder="1080" style="width: 100%;"></el-input-number>
                </el-form-item>
```

### 修改3: 可选 - 调整对话框宽度（第206行）

可以将：
```html
<el-dialog v-model="showCreateDialog" title="新建下载" width="500px">
```

改为：
```html
<el-dialog v-model="showCreateDialog" title="新建下载" width="550px">
```

以及label-width:
```html
<el-form :model="form" label-width="80px">
```

改为：
```html
<el-form :model="form" label-width="100px">
```

这样对话框会更宽一些，容纳代理设置更合适。
