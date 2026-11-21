window.showDiagram = async function (source, dark) {
    const container = document.getElementById('diagram');
    if (dark) {
        document.documentElement.style.colorScheme = 'dark';
        document.body.style.background = '#181a1c';
        document.body.style.color = '#e8eaed';
    }
    try {
        mermaid.initialize({
            startOnLoad: false,
            theme: dark ? 'dark' : 'default',
            securityLevel: 'strict',
            suppressErrorRendering: true,
            maxTextSize: 50000,
            maxEdges: 500,
            secure: ['secure', 'securityLevel', 'maxTextSize', 'maxEdges', 'startOnLoad', 'suppressErrorRendering'],
        });
        const { svg } = await mermaid.render('codexrm-diagram', source);
        container.innerHTML = svg;
        container.querySelectorAll('a').forEach(link => {
            link.removeAttribute('href');
            link.removeAttributeNS('http://www.w3.org/1999/xlink', 'href');
        });
    } catch (error) {
        container.textContent = '无法绘制此图表，可以切换到源码查看。';
    }
};
