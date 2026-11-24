Behaviour.specify(".toolenv-var-checkbox", 'toolenv', 0, function(e) {
    e.onclick = function() {
        let f = document.getElementById('vars.field');
        let v = e.dataset.v;
        // Compare whole names: the name of one variable can be the start of the name of another.
        let others = f.value.split(',').filter(name => name !== '' && name !== v);
        f.value = (e.checked ? others.concat(v) : others).join(',');
    }
});
