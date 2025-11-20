/**
 * Download Lotus 应用主逻辑
 * Vue 3 + Element Plus 下载管理器
 */

const { createApp, ref, onMounted, computed } = Vue;
const { ElMessage, ElMessageBox } = ElementPlus;

createApp({
    setup() {
        const tasks = ref([]);
        const totalTasks = ref(0);
        const currentPage = ref(1);
        const showCreateDialog = ref(false);
        const wsStatus = ref('disconnected'); // WebSocket连接状态: connecting, connected, disconnected
        const wsError = ref('');
        const msgCount = ref(0);
        const wsClient = ref(null);
        const reconnectTimer = ref(null);
        const form = ref({
            url: 'https://officecdn-microsoft-com.akamaized.net/pr/C1297A47-86C4-4C1F-97FA-950631F94777/MacAutoupdate/Microsoft_Office_16.55.21111400_BusinessPro_Installer.pkg',
            threads: 8,
            proxyType: '',
            proxyHost: '',
            proxyPort: null
        });

        // 动态获取基础URL，避免硬编码端口
        const baseUrl = window.location.origin;

        // 预定义颜色池 (HSL生成)
        const colors = Array.from({ length: 32 }, (_, i) => `hsl(${i * 137.508}, 70%, 60%)`);

        const loadTasks = async (page = 1) => {
            const res = await axios.get(`${baseUrl}/api/download/list?page=${page - 1}&size=5`);
            // 这里只更新元数据，实时进度靠WS合并
            // 为防止列表跳动，仅合并数据，不直接覆盖数组引用（如果ID匹配）
            if (tasks.value.length === 0) {
                tasks.value = res.data.content.map(t => ({ ...t, showGrid: false }));
            } else {
                // 简单处理：直接替换，ws会补全
                tasks.value = res.data.content.map(t => ({ ...t, showGrid: false }));
            }
            totalTasks.value = res.data.totalElements;
        };

        const connectWS = () => {
            wsStatus.value = 'connecting';
            wsError.value = '';

            console.log('尝试连接WebSocket:', `${baseUrl}/ws`);
            const socket = new SockJS(`${baseUrl}/ws`);
            const stomp = Stomp.over(socket);
            stomp.debug = null;

            stomp.connect({},
                () => {
                    wsStatus.value = 'connected';
                    wsClient.value = stomp;
                    console.log('WebSocket连接成功');

                    // 订阅进度更新
                    stomp.subscribe('/topic/progress', (msg) => {
                        // console.log('WS Recv:', msg.body); // Debug log
                        msgCount.value++;
                        const updates = JSON.parse(msg.body);
                        updates.forEach(u => {
                            const idx = tasks.value.findIndex(t => t.id === u.id);
                            if (idx !== -1) {
                                // 合并数据，保留UI状态
                                tasks.value[idx] = { ...tasks.value[idx], ...u };
                            }
                        });
                    });

                    // 连接成功后立即加载任务列表
                    loadTasks();
                },
                (error) => {
                    wsStatus.value = 'error';
                    wsError.value = error.toString();
                    console.error('WebSocket连接失败:', error);
                    // 5秒后重连
                    setTimeout(connectWS, 5000);
                }
            );
            socket.onclose = () => {
                if (wsStatus.value === 'connected') {
                    wsStatus.value = 'disconnected';
                    console.log('WebSocket连接关闭，尝试重连...');
                    reconnectTimer.value = setTimeout(() => {
                        connectWS();
                    }, 3000);
                }
            };
        };

        const createTask = async () => {
            if (!form.value.url) return;
            // 确保始终使用固定路径
            const payload = { ...form.value, path: './Temp' };
            const response = await axios.post(`${baseUrl}/api/download/start`, payload);
            const taskId = response.data;

            // 立即创建临时任务对象并添加到列表前端，实现实时显示
            const tempTask = {
                id: taskId,
                fileName: null,
                url: form.value.url,
                status: 'IDLE',
                totalSize: -1,
                downloaded: 0,
                speed: 0,
                createdTime: new Date().toISOString(),
                supportRange: false,
                showGrid: false
            };
            tasks.value.unshift(tempTask);
            totalTasks.value++;

            showCreateDialog.value = false;
            ElMessage.success('任务已创建');
        };

        const downloadToLocal = (task) => {
            // 浏览器直接打开流地址
            window.open(`${baseUrl}/api/download/file/${task.id}`, '_blank');
        };

        const copyDownloadLink = (task) => {
            const downloadUrl = task.url;
            navigator.clipboard.writeText(downloadUrl).then(() => {
                ElMessage.success('下载链接已复制到剪贴板');
            }).catch(() => {
                ElMessage.error('复制失败');
            });
        };

        const confirmDelete = (task) => {
            ElMessageBox.confirm('是否同时删除本地文件?', '删除任务', {
                confirmButtonText: '删任务和文件',
                cancelButtonText: '仅删记录',
                distinguishCancelAndClose: true,
                type: 'warning'
            }).then(() => {
                axios.delete(`${baseUrl}/api/download/${task.id}?deleteFile=true`).then(() => loadTasks(currentPage.value));
            }).catch((action) => {
                if (action === 'cancel') {
                    axios.delete(`${baseUrl}/api/download/${task.id}?deleteFile=false`).then(() => loadTasks(currentPage.value));
                }
            });
        };

        // 400格可视化逻辑
        const getGridMap = (task) => {
            const totalBlocks = 400; // 20x20
            const cells = new Array(totalBlocks).fill(null);

            // 如果任务已完成，仍然显示每个线程的颜色（而不是全绿）
            const isFinished = task.status === 'FINISHED';

            if (!task.chunks) {
                // 如果没有chunks信息但已完成，显示全绿
                if (isFinished) {
                    return cells.fill({ status: 'finished', color: '#67C23A', tip: 'Completed' });
                }
                return cells;
            }

            // 确保 chunks 是数组
            const chunks = Array.isArray(task.chunks) ? task.chunks : Object.values(task.chunks);

            const bytesPerBlock = task.totalSize / totalBlocks;

            chunks.forEach(chunk => {
                if (!chunk) return;

                // 计算该chunk覆盖的 block 范围
                const startIdx = Math.floor((chunk.start || 0) / bytesPerBlock);
                const endIdx = Math.floor((chunk.end || 0) / bytesPerBlock);

                // 兼容处理 AtomicLong 序列化问题
                const currentVal = (typeof chunk.current === 'number') ? chunk.current : (chunk.currentPos || chunk.current || 0);
                const currentIdx = Math.floor(currentVal / bytesPerBlock);

                // 填充颜色
                for (let i = startIdx; i <= Math.min(endIdx, totalBlocks - 1); i++) {
                    let status = 'pending';
                    if (isFinished || i < currentIdx) {
                        status = 'finished'; // 已完成或已下载区域
                    } else if (i === currentIdx) {
                        status = 'downloading'; // 正在下载的头
                    }

                    // 如果该格未被填充或被"更活跃"的状态覆盖
                    if (!cells[i] || status === 'downloading') {
                        cells[i] = {
                            status: status,
                            color: colors[(chunk.colorIndex || 0) % 32],
                            tip: `Thread ${chunk.colorIndex || 0}`
                        };
                    }
                }
            });
            return cells;
        };

        const getCellStyle = (cell) => {
            if (!cell) return {}; // 白色背景
            // 已完成的方块显示线程颜色（稍微加深透明度以示区别）
            if (cell.status === 'finished') return { backgroundColor: cell.color, opacity: 0.8 };
            if (cell.status === 'downloading') return { backgroundColor: cell.color }; // 线程色
            return { backgroundColor: '#eee' }; // 灰色等待
        };

        const formatSize = (size) => {
            if (size == null || size < 0) return 'Unknown';
            if (size === 0) return '0 B';
            const k = 1024, sizes = ['B', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(size) / Math.log(k));
            return parseFloat((size / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        };

        const formatPercent = (task) => {
            if (task.totalSize <= 0) return 0;
            return ((task.downloaded / task.totalSize) * 100).toFixed(1);
        };

        const getStatusType = (s) => {
            if (s === 'FINISHED') return 'success';
            if (s === 'DOWNLOADING') return 'primary';
            if (s === 'ERROR') return 'danger';
            return 'info';
        };

        onMounted(() => {
            loadTasks();
            connectWS();
        });

        return {
            tasks, totalTasks, showCreateDialog, form, wsStatus, wsError, msgCount,
            createTask, handlePageChange: (p) => { currentPage.value = p; loadTasks(p); },
            downloadToLocal, copyDownloadLink, confirmDelete, control: async (id, act) => axios.post(`${baseUrl}/api/download/${id}/${act}`),
            getGridMap, getCellStyle, formatSize, formatPercent, getStatusType
        };
    }
}).use(ElementPlus).mount('#app');
