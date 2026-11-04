// PostToolUse hook: after a source file is edited, remind Claude to run the matching reviewer agent.
// Reads the hook payload from stdin, prints JSON with additionalContext. Never blocks.
let raw = '';
process.stdin.on('data', (c) => (raw += c));
process.stdin.on('end', () => {
  try {
    const path = String(JSON.parse(raw)?.tool_input?.file_path ?? '').replaceAll('\\', '/');
    let agent = null;
    if (/\/claims-web\/.*\.(ts|html|scss|mjs)$/.test(path)) agent = 'angular-reviewer';
    else if (/\.(java|sql)$/.test(path) || /\/src\/main\/resources\/.*\.yml$/.test(path)) agent = 'spring-reviewer';
    if (!agent) return;
    const context =
      `Source file edited (${path}). Before finishing this change, run the ${agent} agent ` +
      `(and code-reviewer for the final pass) on the diff and fix any Blocker findings.`;
    process.stdout.write(
      JSON.stringify({ hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: context } }),
    );
  } catch {
    /* never fail the edit because of the nudge */
  }
});
