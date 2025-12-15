const express = require('express')
const cors = require('cors')
const axios = require('axios')

const PORT = process.env.PORT || 3000
const app = express()
app.use(cors())
app.use(express.json({ limit: '1mb' }))

app.get('/', (_req, res) => {
  res.type('text/plain').send('MashIA backend up')
})

// Real call to OpenAI Chat Completions (low-cost model)
app.post('/api/chat', async (req, res) => {
  try {
    const user = String((req.body && req.body.message) || '').slice(0, 4000)
    if (!user) return res.status(400).type('text/plain').send('Empty message')
    if (!process.env.OPENAI_API_KEY) return res.status(500).type('text/plain').send('Missing OPENAI_API_KEY')

    const response = await axios.post(
      'https://api.openai.com/v1/chat/completions',
      {
        model: 'gpt-4o-mini',
        messages: [
          { role: 'system', content: 'You are a concise helpful assistant.' },
          { role: 'user', content: user }
        ]
      },
      {
        headers: {
          'Authorization': `Bearer ${process.env.OPENAI_API_KEY}`,
          'Content-Type': 'application/json'
        },
        timeout: 25000
      }
    )
    const reply =
      response?.data?.choices?.[0]?.message?.content?.trim?.() || '(no content)'
    res.type('text/plain').send(reply)
  } catch (err) {
    console.error('OpenAI error', err?.response?.status, err?.response?.data || err?.message)
    const code = err?.response?.status || 500
    const body = typeof err?.response?.data === 'string' ? err.response.data : JSON.stringify(err?.response?.data || {})
    res.status(code).type('text/plain').send(body?.slice?.(0, 500) || 'Backend error')
  }
})

app.listen(PORT, '0.0.0.0', () => console.log(`MashIA backend listening on :${PORT}`))
