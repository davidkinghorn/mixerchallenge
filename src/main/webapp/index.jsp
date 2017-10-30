<html>
<head>
<title>Mixer</title>
</head>

<body>

<div id="container">
    <p>Enter new addresses below</p>
    <form id="addressesForm" method="post" action="">
        <div>
            <textarea id="addresses" cols="20" rows="10" style="display: block;"></textarea>

            <input type="submit" id="submit" value="Submit" style="display: block;" />
        </div>
    </form>
</div>
<div id="result"></div>

<script src="https://ajax.googleapis.com/ajax/libs/jquery/3.2.1/jquery.min.js"></script>

<script>
$(document).ready(function(){
	$("#submit").on("click", function(e){
		e.preventDefault();

	    $.ajax({
	         type: "POST",
	         url: "mix",
	         data: { addresses : JSON.stringify($("#addresses").val().split("\n")) }, 
	         success: function(response) {
	             $("#result").empty();
	             $("#result").append("Send coin to " + response);
	         },
	        error: function(response) {
	             $("#result").empty();
	             $("#result").append("There was an error submitting " + response);
	        }
	     });
	});
});
</script>

</body>

</html>