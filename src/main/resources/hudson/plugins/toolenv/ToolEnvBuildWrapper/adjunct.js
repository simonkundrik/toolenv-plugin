Behaviour.specify(".toolenv-var-checkbox", 'toolenv', 0, function(e) {
    e.onclick = function() {
        let f = document.getElementById('vars.field');
        let o = f.value;
        let v = e.dataset.v;
        f.value = e.checked ? (o ? o + `,${v}` : `${v}`) : o.replace(new RegExp(`,${v}`), '').replace(new RegExp(`^${v},?`), '');
    }
});
