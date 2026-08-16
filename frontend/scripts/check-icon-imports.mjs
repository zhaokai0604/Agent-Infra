import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../src', import.meta.url))
const files = []

function walk(dir) {
  for (const ent of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, ent.name)
    if (ent.isDirectory()) walk(p)
    else if (/\.(js|vue)$/.test(ent.name)) files.push(p)
  }
}
walk(root)

const importRe = /import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"]/g
const issues = []

for (const file of files) {
  const text = readFileSync(file, 'utf8')
  const imported = new Set()
  if (text.includes('import * as ElementPlusIconsVue')) {
    continue
  }
  let m
  while ((m = importRe.exec(text)) !== null) {
    for (const part of m[1].split(',')) {
      const name = part.trim().split(/\s+as\s+/)[0].trim()
      if (name) imported.add(name)
    }
  }
  for (const [, name] of text.matchAll(/\bicon:\s*([A-Z][A-Za-z0-9]*)/g)) {
    if (!imported.has(name)) {
      issues.push(`${relative(root, file)}: icon ${name} not imported`)
    }
  }
}

if (issues.length) {
  console.error(issues.join('\n'))
  process.exit(1)
}
console.log('icon import check OK')
