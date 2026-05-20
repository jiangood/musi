" literalmusi.vim - Vim integration for Literal Musi
" Place in ~/.vim/plugin/ or add to your vimrc with: source /path/to/literalmusi.vim
"
" Set this to your actual pile directory:
let g:literalmusi_pile = expand('~/path/to/literalmusi/pile/')

function! LiteralMusiList()
  execute 'Explore ' . fnameescape(g:literalmusi_pile)
endfunction

function! LiteralMusiSearch()
  let l:pat = input('Search: ')
  if empty(l:pat) | return | endif
  execute 'vimgrep /' . escape(l:pat, '/') . '/j ' . fnameescape(g:literalmusi_pile) . '**/*'
  copen
endfunction

nnoremap <leader>ml :call LiteralMusiList()<CR>
nnoremap <leader>ms :call LiteralMusiSearch()<CR>
