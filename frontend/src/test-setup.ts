import { config } from '@vue/test-utils'
import ElementPlus from 'element-plus'

// 组件测试统一安装 Element Plus：组件按需 import，但 el-* 标签需要全局注册才能解析
config.global.plugins = [ElementPlus]
