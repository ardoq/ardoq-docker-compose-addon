  $(function() {
    var vers = true;
    $("#fillerDiv").hide();
    $("#clear-form").click(function() {
      $("form").find("input, textarea").val("");
      $("form").show();
      $("#error-dialog").hide();
      $("#progress-dialog").hide();
      $("#result-dialog").html("");

    });

    $.url = function(sParam) {
        var sPageURL = decodeURIComponent(window.location.search.substring(1)),
            sURLVariables = sPageURL.split('&'),
            sParameterName,
            i;

        for (i = 0; i < sURLVariables.length; i++) {
            sParameterName = sURLVariables[i].split('=');

            if (sParameterName[0] === sParam) {
                return sParameterName[1] === undefined ? true : sParameterName[1];
            }
        }
    };

    var token = $.url('token');
    if(token === undefined){
        $('#token').parents('.form-group').removeClass('hidden');
    }
    else{
        $('#token').val(token);
    }

    var org = $.url('org');
    if(org === undefined){
        $('#org').parents('.form-group').removeClass('hidden');
    }
    else{
        $('#org').val(org);
    }


    $("form#importForm").on("submit", function(e) {
        e.preventDefault();
        $("form").hide();
        $("#progress-dialog").show();

        var data = $('#yaml').val();
        var url = "/yaml?org="+$('#org').val()
                                       +"&token="+$('#token').val()
                                       +"&wsname="+$('#wsname').val()
                                       +"&repos="+$('#repos').val()
                                       +"&account="+$('#account').val()
                                       +"&password="+$('#password').val();

        $.ajax({
            "type": "POST",
            "url": url,
            "contentType" : 'application/json',
            "data" : data,
            "dataType": 'json',
            "success": function(response) {
                $("#progress-dialog").hide();
                $("#result-dialog").show();
                $("#result-dialog a").attr('href',response.workspaceURL);
            },
            "error": function(e) {
                $("#progress-dialog").hide();
                $("#error-dialog").show();
            }
        });
    });
  });
