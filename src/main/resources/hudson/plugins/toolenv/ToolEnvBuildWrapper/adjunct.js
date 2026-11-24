/*
 * The selected tools are saved in the hidden field vars.field as a comma separated list. An entry is the variable
 * of a tool, such as MAVEN_3_HOME, or NAME=MAVEN_3_HOME to define it under another name.
 */
Behaviour.specify(".toolenv-row", 'toolenv', 0, function(row) {
    const box = row.querySelector('.toolenv-var-checkbox');
    const input = row.querySelector('.toolenv-var-name');
    const tool = box.dataset.v;

    // Compare whole names: the name of one variable can be the start of the name of another.
    const isThisTool = entry => entry.substring(entry.indexOf('=') + 1) === tool;

    const update = function() {
        const field = document.getElementById('vars.field');
        let entries = field.value.split(',').filter(entry => entry !== '');
        if (!box.checked) {
            entries = entries.filter(entry => !isThisTool(entry));
        } else {
            const name = input.value.trim();
            const entry = name !== '' && name !== tool ? `${name}=${tool}` : tool;
            const i = entries.findIndex(isThisTool);
            if (i < 0) {
                entries.push(entry);
            } else {
                entries[i] = entry;
            }
        }
        field.value = entries.join(',');
    };

    box.onclick = update;
    input.oninput = update;
    input.onchange = update;
});
