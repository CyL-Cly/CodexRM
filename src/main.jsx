import './status.css'
import React, { useEffect, useState } from 'react'
import { createRoot } from 'react-dom/client'
import Button from '@hi-ui/button'
import Card from '@hi-ui/card'
import Tag from '@hi-ui/tag'
import '@hi-ui/patch-for-react'
import './components/hiui-design-runtime/styles.css'

const statusLabels = {
  loading: { type: 'default', text: '正在检查' },
  online: { type: 'success', text: '服务正常' },
  unavailable: { type: 'danger', text: '无法确认服务状态' },
}

function ServiceStatus() {
  const [check, setCheck] = useState(0)
  const [result, setResult] = useState({ status: 'loading', version: null })

  useEffect(() => {
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), 5000)
    let active = true

    async function loadStatus() {
      try {
        const response = await fetch('/api/v1/health', { signal: controller.signal, cache: 'no-store' })
        if (!response.ok) throw new Error(`Health check returned ${response.status}`)
        const health = await response.json()
        if (health?.ok !== true || health.service !== 'codexrm') throw new Error('Unexpected health response')
        if (active) setResult({ status: 'online', version: health.version })
      } catch {
        if (active) setResult({ status: 'unavailable', version: null })
      } finally {
        clearTimeout(timeout)
      }
    }

    loadStatus()
    return () => {
      active = false
      clearTimeout(timeout)
      controller.abort()
    }
  }, [check])

  const label = statusLabels[result.status]
  const loading = result.status === 'loading'

  function refresh() {
    setResult({ status: 'loading', version: null })
    setCheck((previous) => previous + 1)
  }

  return (
    <main className="status-page">
      <header className="status-header">
        <p className="status-brand">CodexRM</p>
        <h1>服务状态</h1>
      </header>
      <Card bordered>
        <div className="status-check">
          <div>
            <h2>中继服务</h2>
            <div className="status-result" role="status">
              <Tag type={label.type} appearance="filled">{label.text}</Tag>
              {result.version != null && <span>协议版本 {result.version}</span>}
            </div>
          </div>
          <Button type="primary" loading={loading} disabled={loading} onClick={refresh}>
            重新检查
          </Button>
        </div>
      </Card>
      <a className="status-back" href="/">返回工作区</a>
    </main>
  )
}

createRoot(document.getElementById('root')).render(<ServiceStatus />)
