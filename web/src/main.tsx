import ReactDOM from 'react-dom/client'
import { App as AntApp, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import App from './App'
import './styles.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <ConfigProvider
    locale={zhCN}
    theme={{
      token: {
        colorPrimary: '#2563eb',
        borderRadius: 6,
        colorText: '#172033',
        fontFamily: "Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif",
      },
      components: {
        Table: { headerBg: '#f7f8fa', headerColor: '#495267' },
        Button: { controlHeight: 34 },
      },
    }}
  >
    <AntApp>
      <App />
    </AntApp>
  </ConfigProvider>,
)
