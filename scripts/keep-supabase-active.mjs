import pg from "pg";

const { Client } = pg;

function requiredEnv(names) {
  for (const name of names) {
    const value = process.env[name];
    if (value && value.trim()) return value.trim();
  }
  throw new Error(`Missing required database URL. Set one of: ${names.join(", ")}`);
}

const connectionString = requiredEnv(["SUPABASE_DATABASE_URL", "DATABASE_URL"]);
const client = new Client({
  connectionString,
  // Supabase requires TLS. Default SSL on (set SUPABASE_DB_SSL=false only for a local non-TLS DB).
  ssl: process.env.SUPABASE_DB_SSL === "false" ? false : { rejectUnauthorized: false }
});

try {
  await client.connect();
  const result = await client.query("select now() as server_time_utc");
  console.log(`Supabase keep-alive OK at ${result.rows[0].server_time_utc.toISOString()}`);
} finally {
  await client.end();
}
