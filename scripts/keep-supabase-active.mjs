import pg from "pg";

const { Client } = pg;

function requiredEnv(names) {
  for (const name of names) {
    const value = process.env[name];
    if (value && value.trim()) return value.trim();
  }
  throw new Error(`Missing required database URL. Set one of: ${names.join(", ")}`);
}

function toTransactionPooler(connectionString) {
  if (process.env.SUPABASE_KEEPALIVE_NO_REWRITE === "true") return connectionString;
  return connectionString.replace(
    /(@[^/@]*\.pooler\.supabase\.com):5432\b/i,
    "$1:6543"
  );
}

const rawConnectionString = requiredEnv([
  "SUPABASE_KEEPALIVE_URL",
  "SUPABASE_DATABASE_URL",
  "DATABASE_URL",
]);
const connectionString = toTransactionPooler(rawConnectionString);
if (connectionString !== rawConnectionString) {
  console.log("Routing keep-alive through the Supabase transaction pooler (:6543).");
}

const sslOption = process.env.SUPABASE_DB_SSL === "false" ? false : { rejectUnauthorized: false };

async function pingOnce() {
  const client = new Client({
    connectionString,
    ssl: sslOption,
    connectionTimeoutMillis: 15_000,
    statement_timeout: 15_000,
    query_timeout: 15_000,
    application_name: "supabase-keep-alive",
  });
  try {
    await client.connect();
    const result = await client.query("select now() as server_time_utc");
    console.log(`Supabase keep-alive OK at ${result.rows[0].server_time_utc.toISOString()}`);
  } finally {
    await client.end().catch(() => {});
  }
}

const MAX_ATTEMPTS = 5;
let lastError;
for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
  try {
    await pingOnce();
    process.exit(0);
  } catch (error) {
    lastError = error;
    const code = error?.code ? ` (${error.code})` : "";
    console.warn(`Keep-alive attempt ${attempt}/${MAX_ATTEMPTS} failed${code}: ${error?.message ?? error}`);
    if (attempt < MAX_ATTEMPTS) {
      const delayMs = Math.min(15_000, 2_000 * attempt);
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
}

console.error("Supabase keep-alive failed after all attempts.");
throw lastError;
