import { readFile } from 'node:fs/promises'

const stylePrefix = 'virtual:codexrm-hiui-style:'
const hiuiStyleModule = /[/\\]node_modules[/\\](?:@hi-ui[/\\]|hiui5-).*\.scss\.js$/
const compiledStyle = /\bvar (css_\w+) = ("(?:[^"\\]|\\.)*");\s*__styleInject__\(\1\);/

// HIUI ships component styles as JS that injects <style>. Emit external CSS
// instead so production keeps the Go server's style-src 'self' policy.
export function hiuiExternalStyles() {
  return {
    name: 'codexrm-hiui-external-styles',
    apply: 'build',
    enforce: 'pre',
    transform(source, id) {
      if (!hiuiStyleModule.test(id)) return
      const match = source.match(compiledStyle)
      if (!match) this.error(`Unsupported HIUI style module: ${id}`)
      const specifier = `${stylePrefix}${id}`
      return `import ${JSON.stringify(specifier)}; export default ${match[2]};`
    },
    resolveId(id) {
      if (id.startsWith(stylePrefix)) return `\0${id}.css`
    },
    async load(id) {
      if (!id.startsWith(`\0${stylePrefix}`)) return
      const source = await readFile(id.slice(stylePrefix.length + 1, -4), 'utf8')
      return JSON.parse(source.match(compiledStyle)[2])
    },
  }
}
