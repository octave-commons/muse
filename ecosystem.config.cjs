// pm2 ecosystem for the eta-mu daemon.
// Build first: shadow-cljs release daemon
module.exports = {
  apps: [
    {
      name: "eta-mu-daemon",
      script: "dist-daemon/daemon.js",
      cwd: __dirname,
      autorestart: true,
      watch: false,
      max_memory_restart: "200M",
    },
  ],
};
