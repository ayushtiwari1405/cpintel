import type { Node } from '@vscode/tree-sitter-wasm'

/**
 * Rewrites a C++ program so that, compiled with cpviz.h, it reports its own execution.
 *
 * Every insertion goes on the line it belongs to, never on a new one, so the compiler's line
 * numbers (after the `#line 1` this prepends) are the student's: an error or a step on line 12
 * is line 12 of what they wrote. What is inserted:
 *
 *  - each function body opens a `cpviz::Frame` and registers its parameters;
 *  - `cpviz::step(line)` before every statement in a block, and at the top of each loop body
 *    (so the loop's own line shows once per iteration);
 *  - a `cpviz::Var` after each declaration, so the variable is shown from then on and stops being
 *    shown when its scope ends — RAII does the bookkeeping;
 *  - returns report their value; structs get a member that lists their fields;
 *  - globals are registered once, at start-up.
 *
 * `safe` leaves out the riskier rewrites (return values, struct members, arrays of run-time
 * size); the caller falls back to it when the full version does not compile.
 */
export interface InstrumentOptions { safe?: boolean }

interface Insert { at: number; text: string; seq: number }

type FnMode = 'value' | 'void' | 'lambda'
interface Ctx { mode: FnMode }

const STEP_TYPES = new Set([
  'declaration', 'expression_statement', 'if_statement', 'for_statement', 'for_range_loop',
  'while_statement', 'do_statement', 'return_statement', 'break_statement', 'continue_statement',
  'switch_statement', 'goto_statement', 'labeled_statement',
])

const line = (n: Node) => n.startPosition.row + 1
const endLine = (n: Node) => n.endPosition.row + 1
const lit = (s: string) => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`
const named = (n: Node) => n.namedChildren.filter((c): c is Node => c != null)

interface Declared { name: string; ref: boolean; arraySizes: string[]; fn: boolean }

/** Walks a declarator down to the name it declares. */
function declared(d: Node | null): Declared[] {
  if (!d) return []
  switch (d.type) {
    case 'identifier': case 'field_identifier':
      return [{ name: d.text, ref: false, arraySizes: [], fn: false }]
    case 'init_declarator': case 'pointer_declarator': case 'parenthesized_declarator':
    case 'attributed_declarator':
      return declared(d.childForFieldName('declarator') ?? named(d)[0] ?? null)
    case 'reference_declarator':
      return declared(named(d)[0] ?? null).map(x => ({ ...x, ref: true }))
    case 'array_declarator': {
      const size = d.childForFieldName('size')?.text ?? ''
      return declared(d.childForFieldName('declarator')).map(x => ({
        ...x, arraySizes: [size, ...x.arraySizes],
      }))
    }
    case 'structured_binding_declarator':
      return named(d).filter(c => c.type === 'identifier')
        .map(c => ({ name: c.text, ref: false, arraySizes: [], fn: false }))
    case 'function_declarator':
      return [{ name: '', ref: false, arraySizes: [], fn: true }]
    default:
      return []
  }
}

export function instrument(source: string, root: Node, opts: InstrumentOptions = {}): string {
  const inserts: Insert[] = []
  let seq = 0
  let uid = 0
  const add = (at: number, text: string) => inserts.push({ at, text, seq: seq++ })
  const safe = !!opts.safe

  /** The registration for one declared variable, or null when it cannot be registered. */
  const register = (d: Declared, isStatic: boolean): string | null => {
    if (d.fn || !d.name) return null
    const id = `__cpviz_v${++uid}`
    const kw = isStatic ? 'static ' : ''
    if (d.ref) return `${kw}cpviz::Var ${id}(${lit(d.name)}, ${d.name}, cpviz::by_ref);`
    const runtimeSize = d.arraySizes.some(s => !/^\s*(0[xX][0-9a-fA-F]+|\d+)[uUlL]*\s*$/.test(s))
    if (runtimeSize) {
      if (safe) return null
      // Possibly a variable-length array, which cannot be a template argument: registered as
      // its first element and a length instead. Multi-dimensional ones are tried as they are.
      if (d.arraySizes.length === 1) {
        return `${kw}cpviz::Var ${id}(${lit(d.name)}, ${d.name}, (std::size_t)(${d.arraySizes[0]}));`
      }
    }
    return `${kw}cpviz::Var ${id}(${lit(d.name)}, ${d.name});`
  }

  const declarationVars = (decl: Node, isStatic = false): string => {
    if (/^\s*(extern|typedef)\b/.test(decl.text)) return ''
    const parts: string[] = []
    for (const d of decl.childrenForFieldName('declarator')) {
      if (!d) continue
      for (const x of declared(d)) {
        const r = register(x, isStatic)
        if (r) parts.push(r)
      }
    }
    return parts.join(' ')
  }

  /** Text to put at the top of a body, before what it already holds. */
  const atTop = (body: Node, text: string, stepLine?: number) => {
    const parts = [text, stepLine != null ? `cpviz::step(${stepLine});` : ''].filter(Boolean)
    if (body.type === 'compound_statement') {
      if (parts.length) add(body.startIndex + 1, ` ${parts.join(' ')}`)
    } else {
      // A lone statement: braced, so what we add runs with it, and stepped like any other.
      if (STEP_TYPES.has(body.type)) parts.push(`cpviz::step(${line(body)});`)
      add(body.startIndex, `{ ${parts.map(p => `${p} `).join('')}`)
      add(body.endIndex, ' }')
    }
  }

  const isMacroLoopBody = (stmt: Node) => {
    const prev = stmt.previousNamedSibling
    if (!prev || prev.type !== 'expression_statement') return false
    const last = prev.lastChild
    return !!last && last.isMissing
  }

  const statement = (stmt: Node, ctx: Ctx | null, inCase: boolean) => {
    if (!ctx || !STEP_TYPES.has(stmt.type) || stmt.isMissing || stmt.hasError) return
    if (isMacroLoopBody(stmt)) return
    if (stmt.type === 'expression_statement' && named(stmt).length === 0) return
    add(stmt.startIndex, `cpviz::step(${line(stmt)}); `)
    // A declaration after a case label cannot carry an initialised object a later label could
    // jump past, so those are left unregistered.
    if (stmt.type === 'declaration' && !inCase) {
      // Registered every time control passes, static locals included: the registration
      // belongs to this call of the function, not to the variable's lifetime.
      const vars = declarationVars(stmt)
      if (vars) add(stmt.endIndex, ` ${vars}`)
    }
  }

  const params = (fnDecl: Node | null): string => {
    const list = fnDecl?.childForFieldName('parameters')
    if (!list) return ''
    const parts: string[] = []
    for (const p of named(list)) {
      if (p.type !== 'parameter_declaration' && p.type !== 'optional_parameter_declaration') continue
      for (const x of declared(p.childForFieldName('declarator'))) {
        const r = register(x.arraySizes.length ? { ...x, arraySizes: [] } : x, false)
        if (r) parts.push(r)
      }
    }
    return parts.join(' ')
  }

  const functionDeclarator = (d: Node | null): Node | null => {
    let cur = d
    while (cur && cur.type !== 'function_declarator') {
      cur = cur.childForFieldName('declarator') ?? (cur.type === 'reference_declarator' ? named(cur)[0] ?? null : null)
    }
    return cur
  }

  const fn = (node: Node) => {
    const body = node.childForFieldName('body')
    if (!body || body.type !== 'compound_statement') return
    const head = source.slice(node.startIndex, body.startIndex)
    if (/\b(constexpr|consteval)\b/.test(head)) return   // must stay a constant expression

    let name: string
    let mode: FnMode
    let paramText: string
    let self = ''
    if (node.type === 'lambda_expression') {
      name = 'lambda'
      const decl = node.childForFieldName('declarator')
      paramText = params(decl)
      const trailing = decl ? named(decl).find(c => c.type === 'trailing_return_type') : null
      mode = trailing ? (/^\s*->\s*void\s*$/.test(trailing.text) ? 'void' : 'value') : 'lambda'
      // A named lambda takes the name of the variable it is assigned to.
      const holder = node.parent?.type === 'init_declarator' ? node.parent.childForFieldName('declarator') : null
      if (holder?.type === 'identifier') name = holder.text
    } else {
      const fd = functionDeclarator(node.childForFieldName('declarator'))
      if (!fd) return
      name = fd.childForFieldName('declarator')?.text ?? 'function'
      const type = node.childForFieldName('type')
      const top = node.childForFieldName('declarator')
      const returnsVoid = type?.text === 'void' && top?.type === 'function_declarator'
      const ctor = !type
      mode = returnsVoid || ctor ? 'void' : 'value'
      paramText = name === 'main' ? '' : params(fd)
      const inClass = node.parent?.type === 'field_declaration_list'
      const isStatic = named(node).some(c => c.type === 'storage_class_specifier' && c.text === 'static')
      if (inClass && !isStatic) {
        self = `auto* __cpviz_self = this; cpviz::Var __cpviz_this("this", __cpviz_self);`
      }
    }
    add(body.startIndex + 1,
      ` cpviz::Frame __cpviz_f(${lit(name)}, ${line(node)}, ${endLine(body)}); ${self} ${paramText} __cpviz_f.call();`)
    // Falling off the end: the return is reported while the locals are still there to see.
    if (mode === 'void' || name === 'main') {
      add(body.endIndex - 1, ` cpviz::note_ret_void(__cpviz_f, ${endLine(body)}); `)
    }
    block(body, { mode })
  }

  const returnStatement = (ret: Node, ctx: Ctx | null) => {
    if (!ctx || safe) return
    const expr = named(ret).find(c => c.type !== 'comment') ?? null
    const L = line(ret)
    if (!expr) {
      add(ret.startIndex, `{ cpviz::note_ret_void(__cpviz_f, ${L}); `)
      add(ret.endIndex, ' }')
    } else if (ctx.mode === 'void') {
      // `return f();` in a void function: f's value is void and cannot be shown. The frame's
      // destructor reports the return.
    } else if (expr.type === 'initializer_list' || ctx.mode === 'lambda') {
      add(ret.startIndex, `{ cpviz::note_ret_void(__cpviz_f, ${L}); `)
      add(ret.endIndex, ' }')
    } else if (expr.type === 'identifier' || expr.type === 'this') {
      // Kept as `return x;` so a local is still moved out, not copied.
      add(ret.startIndex, `{ cpviz::note_ret(__cpviz_f, ${L}, ${expr.text}); `)
      add(ret.endIndex, ' }')
    } else {
      add(expr.startIndex, `cpviz::ret(__cpviz_f, ${L}, (`)
      add(expr.endIndex, '))')
    }
  }

  const structDump = (spec: Node) => {
    if (safe) return
    const body = spec.childForFieldName('body')
    if (!body || body.type !== 'field_declaration_list') return
    const fields: string[] = []
    for (const f of named(body)) {
      if (f.type !== 'field_declaration') continue
      if (named(f).some(c => c.type === 'storage_class_specifier')) continue
      if (/^\s*friend\b/.test(f.text)) continue
      const bitfield = named(f).some(c => c.type === 'bitfield_clause')
      for (const d of f.childrenForFieldName('declarator')) {
        for (const x of declared(d)) {
          if (x.fn || !x.name) continue
          fields.push(`__w.field(${lit(x.name)}, ${bitfield ? '+' : ''}${x.name});`)
        }
      }
    }
    add(body.endIndex - 1, ` public: void __cpviz_dump(cpviz::W& __w) const { ${fields.join(' ')} } `)
  }

  /** Statements inside a block, then whatever they contain. */
  const block = (body: Node, ctx: Ctx | null) => {
    for (const child of named(body)) {
      if (child.type === 'case_statement') {
        for (const s of named(child).slice(child.childForFieldName('value') ? 1 : 0)) {
          statement(s, ctx, true)
          walk(s, ctx)
        }
        continue
      }
      statement(child, ctx, false)
      walk(child, ctx)
    }
  }

  const loopBody = (body: Node | null, prefix: string, loopLine: number, ctx: Ctx | null) => {
    if (!body || !ctx) return
    atTop(body, prefix, loopLine)
    if (body.type === 'compound_statement') block(body, ctx)
    else walk(body, ctx)
  }

  const walk = (node: Node, ctx: Ctx | null) => {
    switch (node.type) {
      case 'function_definition':
      case 'lambda_expression':
        fn(node)
        return
      case 'struct_specifier':
      case 'class_specifier':
        structDump(node)
        for (const c of named(node)) walk(c, ctx)
        return
      case 'field_declaration_list':
        for (const c of named(node)) walk(c, ctx)
        return
      case 'compound_statement':
        block(node, ctx)
        return
      case 'if_statement': {
        for (const c of named(node)) {
          if (c.type === 'condition_clause') walk(c, ctx)
        }
        const then = node.childForFieldName('consequence')
        if (then) {
          if (then.type === 'compound_statement' || !ctx) walk(then, ctx)
          else { atTop(then, ''); walk(then, ctx) }
        }
        const alt = node.childForFieldName('alternative')
        const otherwise = alt ? named(alt)[0] : null
        if (otherwise) {
          if (otherwise.type === 'compound_statement' || !ctx) walk(otherwise, ctx)
          else { atTop(otherwise, ''); walk(otherwise, ctx) }
        }
        return
      }
      case 'for_statement': {
        const init = node.childForFieldName('initializer')
        const vars = init?.type === 'declaration' ? declarationVars(init) : ''
        for (const f of ['initializer', 'condition', 'update']) {
          const c = node.childForFieldName(f)
          if (c) walk(c, ctx)
        }
        loopBody(node.childForFieldName('body'), vars, line(node), ctx)
        return
      }
      case 'for_range_loop': {
        const vars = declared(node.childForFieldName('declarator'))
          .map(x => register(x, false)).filter(Boolean).join(' ')
        const right = node.childForFieldName('right')
        if (right) walk(right, ctx)
        loopBody(node.childForFieldName('body'), vars, line(node), ctx)
        return
      }
      case 'while_statement': {
        const cond = node.childForFieldName('condition')
        if (cond) walk(cond, ctx)
        loopBody(node.childForFieldName('body'), '', line(node), ctx)
        return
      }
      case 'do_statement': {
        const body = node.childForFieldName('body')
        if (body && ctx && body.type !== 'compound_statement') atTop(body, '')
        if (body) walk(body, ctx)
        const cond = node.childForFieldName('condition')
        if (cond) walk(cond, ctx)
        return
      }
      case 'return_statement':
        returnStatement(node, ctx)
        for (const c of named(node)) walk(c, ctx)
        return
      case 'translation_unit':
      case 'declaration_list':
        for (const c of named(node)) {
          if (c.type === 'declaration' && !ctx && !c.hasError) {
            const vars = declarationVars(c, true)
            if (vars) add(c.endIndex, ` ${vars}`)
          }
          walk(c, ctx)
        }
        return
      default:
        for (const c of named(node)) walk(c, ctx)
    }
  }

  walk(root, null)

  // Assemble: originals interleaved with insertions, in position order, ties in the order made.
  inserts.sort((a, b) => a.at - b.at || a.seq - b.seq)
  let out = ''
  let pos = 0
  for (const ins of inserts) {
    out += source.slice(pos, ins.at) + ins.text
    pos = ins.at
  }
  out += source.slice(pos)
  return `#include "cpviz.h"\n#line 1\n${out}`
}
