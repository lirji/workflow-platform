import { useEffect } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { useAuth } from 'react-oidc-context'
import { Alert, Button, Divider, Typography } from 'antd'
import {
  DeploymentUnitOutlined,
  LoginOutlined,
  CheckCircleOutlined,
  ApartmentOutlined,
  SafetyCertificateOutlined,
  LockOutlined,
} from '@ant-design/icons'
import { config } from '../config'

/**
 * 品牌登录页(公开路由 /login)。与其他平台(his-web)登录范式一致:左品牌区 + 右 SSO 认证卡。
 * 纯 SSO——workflow 后端只认 Casdoor JWT,无账号密码;点"使用统一身份登录"才 signinRedirect(授权码+PKCE)。
 * returnTo 经 location.state.from 透传 → signinRedirect state → CallbackPage 回跳原深链。
 * Stage1(authEnabled=false)dev 免登录:展示"开发模式"入口直接进控制台(不发起 SSO,无 IdP)。
 */
export default function LoginPage() {
  const auth = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const returnTo = (location.state as { from?: string } | null)?.from ?? '/tasks'

  // 已登录访问登录页 → 回跳原深链/首页,不停留。
  useEffect(() => {
    if (config.authEnabled && auth.isAuthenticated) {
      navigate(returnTo, { replace: true })
    }
  }, [auth.isAuthenticated, navigate, returnTo])

  const features = [
    { icon: <CheckCircleOutlined />, label: '待办审批' },
    { icon: <ApartmentOutlined />, label: '流程编排(BPMN)' },
    { icon: <SafetyCertificateOutlined />, label: '运维治理' },
  ]

  const brand = (
    <div className="wf-login-brand">
      {/* BPMN 流程线稿装饰(纯视觉) */}
      <svg className="wf-login-motif" viewBox="0 0 640 480" fill="none" aria-hidden="true" preserveAspectRatio="xMidYMid slice">
        <g stroke="#fff" strokeWidth="2">
          <path d="M76 300 H140" />
          <rect x="140" y="276" width="112" height="48" rx="12" />
          <path d="M252 300 H300" />
          <path d="M330 272 L360 300 L330 328 L300 300 Z" />
          <path d="M360 300 H404 V214" />
          <rect x="404" y="166" width="112" height="48" rx="12" />
          <path d="M330 328 V404 H404" />
          <rect x="404" y="380" width="112" height="48" rx="12" />
          <path d="M516 190 H560" />
          <path d="M516 404 H560" />
        </g>
        <g fill="#fff">
          <circle cx="60" cy="300" r="16" fillOpacity="0.9" />
          <circle cx="576" cy="190" r="15" fillOpacity="0.9" />
          <circle cx="576" cy="404" r="15" fillOpacity="0.9" />
        </g>
      </svg>

      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <span className="wf-login-badge" aria-hidden>
          <DeploymentUnitOutlined />
        </span>
        <span style={{ fontSize: 30, fontWeight: 800, letterSpacing: 0.5 }}>流程审批中台</span>
      </div>

      <Typography.Title level={2} style={{ color: '#fff', margin: 0, maxWidth: 500, lineHeight: 1.3 }}>
        统一身份认证接入 · 一处登录,全平台通行
      </Typography.Title>
      <Typography.Paragraph style={{ color: 'rgba(255,255,255,0.82)', fontSize: 16, maxWidth: 480, margin: 0 }}>
        经 Casdoor 单点登录(OIDC 授权码 + PKCE),按候选组授权 —— 与其它平台统一身份、统一鉴权。
      </Typography.Paragraph>

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 4 }}>
        {features.map((f) => (
          <span key={f.label} className="wf-login-feature">
            {f.icon}
            {f.label}
          </span>
        ))}
      </div>

      <div style={{ position: 'absolute', left: 64, bottom: 32, color: 'rgba(255,255,255,0.6)', fontSize: 12 }}>
        © 2026 流程审批中台 · 由统一身份平台(Casdoor)保护
      </div>
    </div>
  )

  const authArea = (
    <div className="wf-login-card">
      <span className="wf-login-card-mark" aria-hidden>
        <DeploymentUnitOutlined />
      </span>
      <Typography.Title level={3} style={{ marginTop: 20, marginBottom: 4 }}>
        欢迎登录
      </Typography.Title>

      {config.authEnabled ? (
        <>
          <Typography.Text type="secondary">使用统一身份认证(SSO)登录流程审批中台</Typography.Text>
          {auth.error && (
            <Alert
              type="error"
              showIcon
              role="alert"
              style={{ marginTop: 20 }}
              message="登录出错"
              description={auth.error.message}
            />
          )}
          <Button
            type="primary"
            size="large"
            block
            className="wf-login-sso"
            icon={<LoginOutlined />}
            loading={auth.isLoading || !!auth.activeNavigator}
            style={{ marginTop: 28 }}
            onClick={() => void auth.signinRedirect({ state: { returnTo } })}
          >
            {auth.error ? '重试登录' : auth.activeNavigator ? '正在跳转登录…' : '使用统一身份登录'}
          </Button>
          <Divider style={{ marginTop: 28, marginBottom: 16 }} />
          <div className="wf-login-trust">
            <LockOutlined />
            OIDC 授权码 + PKCE · Casdoor 单点登录
          </div>
        </>
      ) : (
        <>
          <Typography.Text type="secondary">开发模式 · 免登录(未启用鉴权)</Typography.Text>
          <Alert
            type="warning"
            showIcon
            style={{ marginTop: 20 }}
            message="当前为开发模式"
            description="未启用 Casdoor 鉴权(VITE_AUTH_ENABLED=false),可直接进入控制台联调。"
          />
          <Button
            type="primary"
            size="large"
            block
            className="wf-login-sso"
            style={{ marginTop: 28 }}
            onClick={() => navigate('/tasks', { replace: true })}
          >
            进入控制台
          </Button>
        </>
      )}
    </div>
  )

  return (
    <div className="wf-login">
      {brand}
      <div className="wf-login-auth">{authArea}</div>
    </div>
  )
}
