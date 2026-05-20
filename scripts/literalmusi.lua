-- ~/.config/nvim/lua/custom/literalmusi.lua
local M = {}
local pile_path = vim.fn.expand('~/path/to/literalmusi/pile/')

M.list = function()
  require('fzf-lua').files({ cwd = pile_path })
end

M.search = function()
  require('fzf-lua').live_grep({ cwd = pile_path })
end

vim.keymap.set('n', '<leader>ml', M.list, { desc = 'List Music' })
vim.keymap.set('n', '<leader>ms', M.search, { desc = 'Search Music' })

return M
